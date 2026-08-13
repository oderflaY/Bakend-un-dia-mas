package auth

import (
	"context"
	"errors"
	"time"

	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgxpool"
	"golang.org/x/crypto/bcrypt"

	"github.com/oderflaY/Bakend-un-dia-mas/internal/addiction"
)

var (
	ErrEmailTaken   = errors.New("el correo ya está registrado")
	ErrInvalidLogin = errors.New("credenciales inválidas")
)

type Store struct{ db *pgxpool.Pool }

func NewStore(db *pgxpool.Pool) *Store { return &Store{db: db} }

type User struct {
	ID           string
	Email        string
	DisplayName  string
	Role         Role
	PasswordHash string

	// Perfil de recuperación. Viaja en la respuesta de register, login y refresh
	// para que la app sepa, sin una segunda llamada, si tiene que enseñar el
	// onboarding o ir directo al inicio.
	Adicciones    []addiction.Type
	Principal     addiction.Type
	ConsumoDesde  *time.Time
	EnTratamiento bool
}

// OnboardingCompleto es la pregunta que hace la app al abrir. Basta con la
// adicción principal: el resto del perfil se puede completar después, pero sin
// ella no hay nada que contar ni material que elegir.
func (u User) OnboardingCompleto() bool { return u.Principal != "" }

// NewUser son los datos de alta. Es un struct y no seis parámetros porque el
// perfil de recuperación va a seguir creciendo y una firma de nueve argumentos
// posicionales es un error de orden esperando a ocurrir.
type NewUser struct {
	Email         string
	Hash          string
	DisplayName   string
	Adicciones    []addiction.Type
	Principal     addiction.Type
	ConsumoDesde  *time.Time
	EnTratamiento bool
}

func (s *Store) CreateUser(ctx context.Context, in NewUser) (User, error) {
	u := User{
		Email:         in.Email,
		DisplayName:   in.DisplayName,
		Role:          RolePatient,
		Adicciones:    in.Adicciones,
		Principal:     in.Principal,
		ConsumoDesde:  in.ConsumoDesde,
		EnTratamiento: in.EnTratamiento,
	}
	if u.Adicciones == nil {
		u.Adicciones = []addiction.Type{}
	}

	err := s.db.QueryRow(ctx, `
		INSERT INTO users (email, password_hash, display_name,
		                   adicciones, adiccion_principal, consumo_desde, en_tratamiento)
		VALUES (lower($1), $2, $3, $4, $5, $6, $7)
		RETURNING id`,
		in.Email, in.Hash, in.DisplayName,
		addiction.Strings(u.Adicciones), string(in.Principal), in.ConsumoDesde, in.EnTratamiento,
	).Scan(&u.ID)
	if isUniqueViolation(err) {
		return User{}, ErrEmailTaken
	}
	if err != nil {
		return User{}, err
	}
	// El tracker nace con el usuario: así ninguna lectura posterior tiene que
	// tratar el caso "el perfil existe pero su tracker no".
	_, err = s.db.Exec(ctx, `INSERT INTO sobriety_trackers (user_id) VALUES ($1)`, u.ID)
	return u, err
}

func (s *Store) UserByEmail(ctx context.Context, email string) (User, error) {
	var u User
	var adicciones []string
	var principal string
	err := s.db.QueryRow(ctx, `
		SELECT id, email, display_name, role, password_hash,
		       adicciones, adiccion_principal, consumo_desde, en_tratamiento
		FROM users WHERE email = lower($1)`, email).
		Scan(&u.ID, &u.Email, &u.DisplayName, &u.Role, &u.PasswordHash,
			&adicciones, &principal, &u.ConsumoDesde, &u.EnTratamiento)
	if errors.Is(err, pgx.ErrNoRows) {
		return User{}, ErrInvalidLogin
	}
	u.Adicciones = addiction.Types(adicciones)
	u.Principal = addiction.Type(principal)
	return u, err
}

func (s *Store) TouchLogin(ctx context.Context, userID string) error {
	_, err := s.db.Exec(ctx, `UPDATE users SET last_login_at = now() WHERE id = $1`, userID)
	return err
}

func (s *Store) SaveRefreshToken(ctx context.Context, hash, userID string, expires time.Time) error {
	_, err := s.db.Exec(ctx, `
		INSERT INTO refresh_tokens (token_hash, user_id, expires_at)
		VALUES ($1, $2, $3)`, hash, userID, expires)
	return err
}

// ConsumeRefreshToken revoca el token y devuelve a su dueño en una sola
// sentencia: dos peticiones simultáneas con el mismo token no pueden ganar ambas.
func (s *Store) ConsumeRefreshToken(ctx context.Context, hash string) (User, error) {
	var u User
	var adicciones []string
	var principal string
	err := s.db.QueryRow(ctx, `
		WITH consumed AS (
			UPDATE refresh_tokens SET revoked_at = now()
			WHERE token_hash = $1 AND revoked_at IS NULL AND expires_at > now()
			RETURNING user_id
		)
		SELECT u.id, u.email, u.display_name, u.role,
		       u.adicciones, u.adiccion_principal, u.consumo_desde, u.en_tratamiento
		FROM consumed JOIN users u ON u.id = consumed.user_id`, hash).
		Scan(&u.ID, &u.Email, &u.DisplayName, &u.Role,
			&adicciones, &principal, &u.ConsumoDesde, &u.EnTratamiento)
	if errors.Is(err, pgx.ErrNoRows) {
		return User{}, ErrInvalidLogin
	}
	// El perfil también viaja en el refresh: si la persona completó su
	// onboarding en otro dispositivo, este se entera al renovar el token.
	u.Adicciones = addiction.Types(adicciones)
	u.Principal = addiction.Type(principal)
	return u, err
}

func (s *Store) RevokeAllTokens(ctx context.Context, userID string) error {
	_, err := s.db.Exec(ctx, `
		UPDATE refresh_tokens SET revoked_at = now()
		WHERE user_id = $1 AND revoked_at IS NULL`, userID)
	return err
}

// ------------------------------------------------- recuperar la contraseña

// ResetReciente dice si a esa persona ya se le mandó un código hace poco. Es el
// freno contra usar la recuperación para inundarle el buzón a alguien.
func (s *Store) ResetReciente(ctx context.Context, userID string, dentroDe time.Duration) (bool, error) {
	var existe bool
	err := s.db.QueryRow(ctx, `
		SELECT EXISTS(
			SELECT 1 FROM password_resets
			WHERE user_id = $1 AND usado_en IS NULL AND created_at > now() - $2::interval
		)`, userID, dentroDe.String()).Scan(&existe)
	return existe, err
}

func (s *Store) CrearReset(ctx context.Context, userID, codeHash string, expira time.Time) error {
	_, err := s.db.Exec(ctx, `
		INSERT INTO password_resets (user_id, code_hash, expires_at)
		VALUES ($1, $2, $3)`, userID, codeHash, expira)
	return err
}

// UsarReset comprueba el código y cambia la contraseña. Todo va en una
// transacción porque son tres escrituras que solo tienen sentido juntas: gastar
// el código, cambiar el hash y tirar las sesiones abiertas. Si la última fallara
// por su cuenta, quien haya entrado con la contraseña vieja seguiría dentro
// después de que su víctima la cambiara, que es el escenario exacto del que
// alguien huye al recuperar una cuenta.
func (s *Store) UsarReset(ctx context.Context, email, codigo, passwordHash string) error {
	tx, err := s.db.Begin(ctx)
	if err != nil {
		return err
	}
	defer tx.Rollback(ctx) //nolint:errcheck // no-op tras commit

	var resetID, codeHash, userID string
	// El más reciente que siga vivo: sin usar, sin caducar y con intentos de
	// sobra. FOR UPDATE serializa dos verificaciones simultáneas del mismo
	// código, para que el contador de intentos no se pierda entre ellas.
	err = tx.QueryRow(ctx, `
		SELECT pr.id::text, pr.code_hash, pr.user_id::text
		FROM password_resets pr
		JOIN users u ON u.id = pr.user_id
		WHERE lower(u.email) = lower($1)
		  AND pr.usado_en IS NULL
		  AND pr.expires_at > now()
		  AND pr.intentos < $2
		ORDER BY pr.created_at DESC
		LIMIT 1
		FOR UPDATE OF pr`, email, resetMaxIntentos).Scan(&resetID, &codeHash, &userID)
	if errors.Is(err, pgx.ErrNoRows) {
		return ErrResetInvalido
	}
	if err != nil {
		return err
	}

	if bcrypt.CompareHashAndPassword([]byte(codeHash), []byte(codigo)) != nil {
		// El intento fallido se cobra aunque la transacción no cambie nada más.
		// Por eso se hace commit: con rollback, el contador nunca subiría y los
		// cinco intentos serían infinitos.
		if _, err := tx.Exec(ctx,
			`UPDATE password_resets SET intentos = intentos + 1 WHERE id = $1`, resetID); err != nil {
			return err
		}
		if err := tx.Commit(ctx); err != nil {
			return err
		}
		return ErrResetInvalido
	}

	if _, err := tx.Exec(ctx,
		`UPDATE password_resets SET usado_en = now() WHERE id = $1`, resetID); err != nil {
		return err
	}
	if _, err := tx.Exec(ctx,
		`UPDATE users SET password_hash = $2 WHERE id = $1`, userID, passwordHash); err != nil {
		return err
	}
	// Cambiar la contraseña cierra todas las sesiones, también las del
	// dispositivo desde el que se está cambiando.
	if _, err := tx.Exec(ctx, `
		UPDATE refresh_tokens SET revoked_at = now()
		WHERE user_id = $1 AND revoked_at IS NULL`, userID); err != nil {
		return err
	}
	return tx.Commit(ctx)
}

func isUniqueViolation(err error) bool {
	var pgErr interface{ SQLState() string }
	return errors.As(err, &pgErr) && pgErr.SQLState() == "23505"
}
