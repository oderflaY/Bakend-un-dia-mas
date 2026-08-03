package auth

import (
	"context"
	"errors"
	"time"

	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgxpool"
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
}

func (s *Store) CreateUser(ctx context.Context, email, hash, displayName string) (User, error) {
	u := User{Email: email, DisplayName: displayName, Role: RolePatient}
	err := s.db.QueryRow(ctx, `
		INSERT INTO users (email, password_hash, display_name)
		VALUES (lower($1), $2, $3)
		RETURNING id`, email, hash, displayName).Scan(&u.ID)
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
	err := s.db.QueryRow(ctx, `
		SELECT id, email, display_name, role, password_hash
		FROM users WHERE email = lower($1)`, email).
		Scan(&u.ID, &u.Email, &u.DisplayName, &u.Role, &u.PasswordHash)
	if errors.Is(err, pgx.ErrNoRows) {
		return User{}, ErrInvalidLogin
	}
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
	err := s.db.QueryRow(ctx, `
		WITH consumed AS (
			UPDATE refresh_tokens SET revoked_at = now()
			WHERE token_hash = $1 AND revoked_at IS NULL AND expires_at > now()
			RETURNING user_id
		)
		SELECT u.id, u.email, u.display_name, u.role
		FROM consumed JOIN users u ON u.id = consumed.user_id`, hash).
		Scan(&u.ID, &u.Email, &u.DisplayName, &u.Role)
	if errors.Is(err, pgx.ErrNoRows) {
		return User{}, ErrInvalidLogin
	}
	return u, err
}

func (s *Store) RevokeAllTokens(ctx context.Context, userID string) error {
	_, err := s.db.Exec(ctx, `
		UPDATE refresh_tokens SET revoked_at = now()
		WHERE user_id = $1 AND revoked_at IS NULL`, userID)
	return err
}

func isUniqueViolation(err error) bool {
	var pgErr interface{ SQLState() string }
	return errors.As(err, &pgErr) && pgErr.SQLState() == "23505"
}
