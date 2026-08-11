// Package community es el muro de historias de recuperación.
//
// Seis decisiones gobiernan todo el paquete. Cambiar cualquiera de ellas sin
// entender el porqué rompe algo que no se ve en los tests:
//
//  1. **El servidor nunca devuelve el user_id del autor, solo el alias.** Aquí
//     la gente cuenta que está en recuperación; poder cruzar un alias con una
//     cuenta es lo peor que puede filtrar esta app. `author_id` existe en la
//     tabla porque hace falta para borrar, bloquear y moderar, y no sale de
//     este paquete.
//  2. **Paginación por cursor, no por número.** El muro ordena por racha, y esa
//     cifra crece cada día: con OFFSET las filas se desplazan entre peticiones
//     y salen historias repetidas al avanzar.
//  3. **`alias_snapshot` y `streak_days` se congelan al publicar.** Cambiar el
//     alias solo desliga hacia adelante. Y la racha no se recalcula: contar 214
//     días y haber recaído después es justo la clase de experiencia que sirve
//     leer.
//  4. **El umbral son 30 días.** Suficiente para tener algo que contar, bajo
//     para que no sea un club de veteranos: alguien con dos meses le habla
//     mucho mejor a quien lleva dos días que alguien con cinco años.
//  5. **404 en vez de 403** para historias ajenas, igual que en el resto de la
//     API.
//  6. **Los datos personales avisan pero no bloquean** (ver moderacion.go).
package community

import (
	"context"
	"encoding/base64"
	"errors"
	"fmt"
	"strconv"
	"strings"
	"time"

	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgxpool"
)

const (
	// MinDiasRacha es el umbral para publicar. Ver la decisión 4.
	MinDiasRacha = 30

	// reportesParaRevision: con tres personas distintas señalando la misma
	// historia, se retira del muro sola y espera revisión humana. Tres y no uno
	// para que nadie pueda silenciar a otro; tres y no diez porque el muro es
	// pequeño y diez tardarían días.
	reportesParaRevision = 3

	maxAlias    = 24
	minAlias    = 3
	maxTitulo   = 120
	maxCuerpo   = 4000
	minCuerpo   = 80 // menos que esto no es una historia, es un comentario
	maxObjetivo = 60
	maxDetalle  = 500
)

var (
	ErrNoElegible    = errors.New("todavía no llegas a los 30 días de racha")
	ErrAliasTomado   = errors.New("ese alias ya está en uso")
	ErrAliasInvalido = errors.New("el alias necesita entre 3 y 24 caracteres, letras y números")
	ErrSinAlias      = errors.New("elige un alias antes de publicar")
	ErrNoEncontrada  = errors.New("no existe esa historia")
	ErrPropia        = errors.New("es tu propia historia")
)

// Estado de una historia.
const (
	Publicada  = "PUBLICADA"
	EnRevision = "EN_REVISION"
	Retirada   = "RETIRADA"
)

// Perfil es la identidad pública de alguien en el muro.
type Perfil struct {
	Alias      string     `json:"alias"`
	DiasRacha  int        `json:"diasRacha"`
	Elegible   bool       `json:"elegible"`
	FaltanDias int        `json:"faltanDias"`
	Historias  int        `json:"misHistorias"`
	CreatedAt  *time.Time `json:"aliasDesde"`
}

// Story es lo que viaja a la app. **No tiene campo de autor**: solo el alias.
// Ver la decisión 1.
type Story struct {
	ID        string    `json:"id"`
	Alias     string    `json:"alias"`
	DiasRacha *int      `json:"diasRacha"` // null si eligió no compartirla
	Titulo    string    `json:"titulo"`
	Cuerpo    string    `json:"cuerpo"`
	Objetivo  string    `json:"objetivo"`
	Estado    string    `json:"estado"`
	Utiles    int       `json:"utiles"`
	MeAyudo   bool      `json:"meAyudo"`
	EsMia     bool      `json:"esMia"`
	CreatedAt time.Time `json:"createdAt"`
}

// NuevaStory es lo que llega en el POST.
type NuevaStory struct {
	Titulo         string
	Cuerpo         string
	Objetivo       string
	CompartirRacha bool
}

// Orden del muro. Son los tres que enseña la app.
type Orden string

const (
	OrdenRacha     Orden = "racha" // "Más tiempo"
	OrdenRecientes Orden = "recientes"
	OrdenUtiles    Orden = "utiles"
)

func ParseOrden(s string) Orden {
	switch strings.ToLower(strings.TrimSpace(s)) {
	case "recientes", "reciente", "nuevas":
		return OrdenRecientes
	case "utiles", "útiles", "useful":
		return OrdenUtiles
	default:
		return OrdenRacha
	}
}

type Store struct{ db *pgxpool.Pool }

func NewStore(db *pgxpool.Pool) *Store { return &Store{db: db} }

// ---------------------------------------------------------------- perfil

// Perfil devuelve la identidad pública y si ya puede publicar. La racha se lee
// del tracker en vivo: es la única cifra del paquete que **no** está congelada,
// porque describe a la persona ahora y no a una historia de entonces.
func (s *Store) Perfil(ctx context.Context, userID string) (Perfil, error) {
	var p Perfil
	var alias *string
	var creado *time.Time
	var segundos *int64

	err := s.db.QueryRow(ctx, `
		SELECT cp.alias, cp.created_at,
		       EXTRACT(EPOCH FROM (now() - st.start_date))::bigint,
		       (SELECT count(*) FROM community_stories
		         WHERE author_id = $1 AND estado <> 'RETIRADA')
		FROM users u
		LEFT JOIN community_profiles cp ON cp.user_id = u.id
		LEFT JOIN sobriety_trackers  st ON st.user_id = u.id
		WHERE u.id = $1`, userID).
		Scan(&alias, &creado, &segundos, &p.Historias)
	if err != nil {
		return Perfil{}, err
	}

	if alias != nil {
		p.Alias = *alias
	}
	p.CreatedAt = creado
	if segundos != nil && *segundos > 0 {
		p.DiasRacha = int(*segundos / 86400)
	}
	p.Elegible = p.DiasRacha >= MinDiasRacha
	if !p.Elegible {
		p.FaltanDias = MinDiasRacha - p.DiasRacha
	}
	return p, nil
}

// GuardarAlias crea o cambia el alias. No toca las historias ya publicadas: ver
// la decisión 3.
func (s *Store) GuardarAlias(ctx context.Context, userID, alias string) (Perfil, error) {
	alias = normalizaAlias(alias)
	if !aliasValido(alias) {
		return Perfil{}, ErrAliasInvalido
	}

	_, err := s.db.Exec(ctx, `
		INSERT INTO community_profiles (user_id, alias)
		VALUES ($1, $2)
		ON CONFLICT (user_id) DO UPDATE SET alias = EXCLUDED.alias, updated_at = now()`,
		userID, alias)
	if isUniqueViolation(err) {
		return Perfil{}, ErrAliasTomado
	}
	if err != nil {
		return Perfil{}, err
	}
	return s.Perfil(ctx, userID)
}

// ---------------------------------------------------------------- muro

// Página es un tramo del muro más el cursor para pedir el siguiente.
type Pagina struct {
	Items           []Story `json:"items"`
	SiguienteCursor string  `json:"siguienteCursor,omitempty"`
}

// Listar arma el muro. Tres filtros que no se ven en la firma:
//
//   - se ocultan las historias de autores bloqueados por quien mira;
//   - se ocultan las que no están publicadas, salvo las propias (si una historia
//     tuya entra en revisión tienes que poder verlo);
//   - el cursor es opaco y codifica la clave del orden vigente, así que cambiar
//     de pestaña invalida el cursor por construcción.
func (s *Store) Listar(ctx context.Context, userID string, orden Orden, limite int, cursor string) (Pagina, error) {
	clave, id, err := decodeCursor(cursor)
	if err != nil {
		return Pagina{}, err
	}

	// El SQL se arma por orden y no con un CASE dentro del WHERE porque cada
	// variante tiene que poder usar su índice parcial.
	//
	// Los parámetros del cursor solo se añaden cuando hay cursor: pgx exige que
	// el número de argumentos coincida exactamente con los placeholders usados,
	// así que no vale dejarlos siempre y que la condición los ignore.
	args := []any{userID, limite + 1}
	cond := "TRUE"

	var orderBy string
	switch orden {
	case OrdenRecientes:
		orderBy = "s.created_at DESC, s.id DESC"
		if cursor != "" {
			cond = "(s.created_at, s.id) < ($3::timestamptz, $4::uuid)"
		}
	case OrdenUtiles:
		orderBy = "s.useful_count DESC, s.id DESC"
		if cursor != "" {
			cond = "(s.useful_count, s.id) < ($3::int, $4::uuid)"
		}
	default:
		// NULLS LAST: quien no comparte su racha aparece después de quien sí, no
		// al principio. COALESCE a -1 hace que el cursor funcione igual.
		orderBy = "COALESCE(s.streak_days, -1) DESC, s.id DESC"
		if cursor != "" {
			cond = "(COALESCE(s.streak_days, -1), s.id) < ($3::int, $4::uuid)"
		}
	}
	if cursor != "" {
		args = append(args, clave, id)
	}

	// Se pide una fila de más para saber si hay página siguiente sin contar.
	sql := fmt.Sprintf(`
		SELECT s.id, s.alias_snapshot, s.streak_days, s.titulo, s.cuerpo, s.objetivo,
		       s.estado, s.useful_count, s.created_at,
		       EXISTS(SELECT 1 FROM community_story_useful u
		               WHERE u.story_id = s.id AND u.user_id = $1) AS me_ayudo,
		       s.author_id = $1 AS es_mia
		FROM community_stories s
		WHERE (s.estado = 'PUBLICADA' OR s.author_id = $1)
		  AND NOT EXISTS (SELECT 1 FROM community_blocks b
		                   WHERE b.user_id = $1 AND b.blocked_id = s.author_id)
		  AND %s
		ORDER BY %s
		LIMIT $2`, cond, orderBy)

	rows, err := s.db.Query(ctx, sql, args...)
	if err != nil {
		return Pagina{}, err
	}
	defer rows.Close()

	page := Pagina{Items: []Story{}}
	var ultimaClave string
	for rows.Next() {
		var st Story
		var dias *int
		var creado time.Time
		var utiles int
		if err := rows.Scan(&st.ID, &st.Alias, &dias, &st.Titulo, &st.Cuerpo, &st.Objetivo,
			&st.Estado, &utiles, &creado, &st.MeAyudo, &st.EsMia); err != nil {
			return Pagina{}, err
		}
		st.DiasRacha = dias
		st.Utiles = utiles
		st.CreatedAt = creado

		switch orden {
		case OrdenRecientes:
			ultimaClave = claveFecha(creado)
		case OrdenUtiles:
			ultimaClave = strconv.Itoa(utiles)
		default:
			d := -1
			if dias != nil {
				d = *dias
			}
			ultimaClave = strconv.Itoa(d)
		}
		page.Items = append(page.Items, st)
	}
	if err := rows.Err(); err != nil {
		return Pagina{}, err
	}

	if len(page.Items) > limite {
		page.Items = page.Items[:limite]
		ultima := page.Items[len(page.Items)-1]
		// La clave del cursor se recalcula sobre la última fila que SÍ se
		// devuelve, no sobre la que sobró.
		switch orden {
		case OrdenRecientes:
			ultimaClave = claveFecha(ultima.CreatedAt)
		case OrdenUtiles:
			ultimaClave = strconv.Itoa(ultima.Utiles)
		default:
			d := -1
			if ultima.DiasRacha != nil {
				d = *ultima.DiasRacha
			}
			ultimaClave = strconv.Itoa(d)
		}
		page.SiguienteCursor = encodeCursor(ultimaClave, ultima.ID)
	}
	return page, nil
}

// ---------------------------------------------------------------- historias

// Publicar congela alias y racha en el INSERT. Ver la decisión 3.
func (s *Store) Publicar(ctx context.Context, userID string, in NuevaStory) (Story, error) {
	p, err := s.Perfil(ctx, userID)
	if err != nil {
		return Story{}, err
	}
	if p.Alias == "" {
		return Story{}, ErrSinAlias
	}
	if !p.Elegible {
		return Story{}, ErrNoElegible
	}

	var dias *int
	if in.CompartirRacha {
		d := p.DiasRacha
		dias = &d
	}

	st := Story{
		Alias:     p.Alias,
		DiasRacha: dias,
		Titulo:    in.Titulo,
		Cuerpo:    in.Cuerpo,
		Objetivo:  in.Objetivo,
		Estado:    Publicada,
		EsMia:     true,
	}
	err = s.db.QueryRow(ctx, `
		INSERT INTO community_stories
			(author_id, alias_snapshot, streak_days, titulo, cuerpo, objetivo)
		VALUES ($1, $2, $3, $4, $5, $6)
		RETURNING id, created_at`,
		userID, p.Alias, dias, in.Titulo, in.Cuerpo, in.Objetivo).
		Scan(&st.ID, &st.CreatedAt)
	return st, err
}

// Borrar solo puede borrarla su autor. Un id ajeno responde igual que un id que
// no existe: ver la decisión 5.
func (s *Store) Borrar(ctx context.Context, userID, storyID string) error {
	tag, err := s.db.Exec(ctx,
		`DELETE FROM community_stories WHERE id = $1 AND author_id = $2`, storyID, userID)
	if isBadUUID(err) {
		return ErrNoEncontrada
	}
	if err != nil {
		return err
	}
	if tag.RowsAffected() == 0 {
		return ErrNoEncontrada
	}
	return nil
}

// MarcarUtil pone o quita el "Me ayudó". Es idempotente: marcar dos veces deja
// el mismo estado, porque la app puede reintentar sin red.
//
// El contador se mantiene dentro de la misma transacción que el voto; si se
// recalculara con un COUNT en cada lectura, el orden "Más útiles" costaría un
// escaneo por fila del muro.
func (s *Store) MarcarUtil(ctx context.Context, userID, storyID string, util bool) (int, error) {
	tx, err := s.db.Begin(ctx)
	if err != nil {
		return 0, err
	}
	defer tx.Rollback(ctx) //nolint:errcheck // no-op tras commit

	// Se comprueba que exista y sea visible antes de tocar nada: si no, el
	// contador podría moverse para una historia retirada.
	var autor string
	err = tx.QueryRow(ctx, `
		SELECT author_id::text FROM community_stories
		WHERE id = $1 AND estado = 'PUBLICADA'`, storyID).Scan(&autor)
	if errors.Is(err, pgx.ErrNoRows) || isBadUUID(err) {
		return 0, ErrNoEncontrada
	}
	if err != nil {
		return 0, err
	}

	var tag pgconnTag
	if util {
		tag, err = tx.Exec(ctx, `
			INSERT INTO community_story_useful (story_id, user_id) VALUES ($1, $2)
			ON CONFLICT DO NOTHING`, storyID, userID)
	} else {
		tag, err = tx.Exec(ctx, `
			DELETE FROM community_story_useful WHERE story_id = $1 AND user_id = $2`,
			storyID, userID)
	}
	if err != nil {
		return 0, err
	}

	delta := 0
	if tag.RowsAffected() > 0 {
		delta = 1
		if !util {
			delta = -1
		}
	}

	var total int
	if err := tx.QueryRow(ctx, `
		UPDATE community_stories SET useful_count = GREATEST(useful_count + $2, 0)
		WHERE id = $1 RETURNING useful_count`, storyID, delta).Scan(&total); err != nil {
		return 0, err
	}
	return total, tx.Commit(ctx)
}

// pgconnTag evita importar pgconn solo por el tipo del resultado de Exec.
type pgconnTag interface{ RowsAffected() int64 }

// Reportar registra el reporte y, al tercero de personas distintas, manda la
// historia a revisión. La transición la hace la misma sentencia que cuenta, así
// que dos reportes simultáneos no pueden dejarla en 3 sin revisar.
func (s *Store) Reportar(ctx context.Context, userID, storyID, motivo, detalle string) (string, error) {
	tx, err := s.db.Begin(ctx)
	if err != nil {
		return "", err
	}
	defer tx.Rollback(ctx) //nolint:errcheck // no-op tras commit

	var autor string
	err = tx.QueryRow(ctx,
		`SELECT author_id::text FROM community_stories WHERE id = $1`, storyID).Scan(&autor)
	if errors.Is(err, pgx.ErrNoRows) || isBadUUID(err) {
		return "", ErrNoEncontrada
	}
	if err != nil {
		return "", err
	}
	if autor == userID {
		return "", ErrPropia
	}

	tag, err := tx.Exec(ctx, `
		INSERT INTO community_reports (story_id, reporter_id, motivo, detalle)
		VALUES ($1, $2, $3, $4)
		ON CONFLICT (story_id, reporter_id) DO NOTHING`, storyID, userID, motivo, detalle)
	if err != nil {
		return "", err
	}

	var estado string
	if tag.RowsAffected() > 0 {
		err = tx.QueryRow(ctx, `
			UPDATE community_stories
			SET reports_count = reports_count + 1,
			    estado = CASE WHEN reports_count + 1 >= $2 AND estado = 'PUBLICADA'
			                  THEN 'EN_REVISION'::story_state ELSE estado END
			WHERE id = $1
			RETURNING estado::text`, storyID, reportesParaRevision).Scan(&estado)
	} else {
		err = tx.QueryRow(ctx,
			`SELECT estado::text FROM community_stories WHERE id = $1`, storyID).Scan(&estado)
	}
	if err != nil {
		return "", err
	}
	return estado, tx.Commit(ctx)
}

// BloquearAutor esconde del muro todo lo de quien escribió esa historia. El
// bloqueo se guarda contra el user_id, no contra el alias, para que sobreviva a
// un cambio de nombre; el user_id no sale de aquí.
func (s *Store) BloquearAutor(ctx context.Context, userID, storyID string) error {
	var autor string
	err := s.db.QueryRow(ctx,
		`SELECT author_id::text FROM community_stories WHERE id = $1`, storyID).Scan(&autor)
	if errors.Is(err, pgx.ErrNoRows) || isBadUUID(err) {
		return ErrNoEncontrada
	}
	if err != nil {
		return err
	}
	if autor == userID {
		return ErrPropia
	}

	_, err = s.db.Exec(ctx, `
		INSERT INTO community_blocks (user_id, blocked_id) VALUES ($1, $2)
		ON CONFLICT DO NOTHING`, userID, autor)
	return err
}

// ---------------------------------------------------------------- utilidades

func normalizaAlias(s string) string {
	return strings.Join(strings.Fields(s), " ")
}

// aliasValido acepta letras, números, espacios, guiones y guiones bajos. Sin
// arrobas ni puntos: un alias que parece un usuario de red social o un correo
// invita a buscar a esa persona fuera del muro.
func aliasValido(s string) bool {
	n := len([]rune(s))
	if n < minAlias || n > maxAlias {
		return false
	}
	for _, r := range s {
		switch {
		case r >= 'a' && r <= 'z', r >= 'A' && r <= 'Z',
			r >= '0' && r <= '9', r == ' ', r == '-', r == '_':
		case strings.ContainsRune("áéíóúüñÁÉÍÓÚÜÑ", r):
		default:
			return false
		}
	}
	return true
}

// claveFecha va con nanosegundos y no con segundos enteros: dos historias
// publicadas en el mismo segundo son lo normal en el muro, y un cursor truncado
// a segundos se salta todas las que caen dentro del mismo.
func claveFecha(t time.Time) string {
	return t.UTC().Format(time.RFC3339Nano)
}

// El cursor es opaco: la app lo guarda y lo devuelve sin interpretarlo. Va en
// base64 para que nadie construya uno a mano y acabe dependiendo del formato.
func encodeCursor(clave, id string) string {
	return base64.RawURLEncoding.EncodeToString([]byte(clave + "|" + id))
}

func decodeCursor(c string) (string, string, error) {
	if c == "" {
		// Valores neutros: la consulta los ignora porque la condición es TRUE.
		return "0", "00000000-0000-0000-0000-000000000000", nil
	}
	raw, err := base64.RawURLEncoding.DecodeString(c)
	if err != nil {
		return "", "", errors.New("cursor inválido")
	}
	clave, id, ok := strings.Cut(string(raw), "|")
	if !ok || clave == "" || id == "" {
		return "", "", errors.New("cursor inválido")
	}
	return clave, id, nil
}

func isUniqueViolation(err error) bool { return sqlState(err) == "23505" }
func isBadUUID(err error) bool         { return sqlState(err) == "22P02" }

func sqlState(err error) string {
	var pgErr interface{ SQLState() string }
	if errors.As(err, &pgErr) {
		return pgErr.SQLState()
	}
	return ""
}
