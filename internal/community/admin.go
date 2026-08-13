package community

// La cola de moderación: lo que ve y hace quien atiende los reportes del muro.
//
// El muro se defiende solo hasta cierto punto —a los tres reportes de personas
// distintas una historia se retira sola— pero ahí se acaba lo automático. Sin
// alguien que mire la cola, ese mecanismo solo sabe esconder: una historia
// legítima señalada por tres personas coordinadas se queda escondida para
// siempre, y quien publica algo de verdad peligroso solo pierde esa publicación.
// Las dos cosas se arreglan aquí.
//
// Una diferencia importante con el resto del paquete: **esto sí devuelve el id
// del autor**. Todas las lecturas del muro esconden `author_id` a propósito
// (decisión 1 de 0004_comunidad.sql), pero moderar sin poder distinguir "tres
// historias de tres personas" de "tres historias de la misma persona" es moderar
// a ciegas. El id se devuelve solo bajo rol admin y nunca sale de esa ruta.

import (
	"context"
	"errors"
	"time"

	"github.com/jackc/pgx/v5"
)

// EnCola es una historia esperando revisión, con lo que hace falta para decidir.
type EnCola struct {
	ID string `json:"id"`
	// AutorID no aparece en ningún otro JSON de este paquete. Ver la cabecera.
	AutorID   string    `json:"autorId"`
	Alias     string    `json:"alias"`
	Titulo    string    `json:"titulo"`
	Cuerpo    string    `json:"cuerpo"`
	Objetivo  string    `json:"objetivo"`
	CreatedAt time.Time `json:"createdAt"`
	// Historial del autor: cuántas de sus historias se han retirado antes. Es lo
	// que separa un mal día de un patrón.
	RetiradasPrevias int       `json:"retiradasPrevias"`
	Reportes         []Reporte `json:"reportes"`
	// Avisos del filtro automático, recalculados sobre el texto actual. Lo que
	// detecta moderacion.go al publicar no se guarda, así que se vuelve a pasar
	// aquí: al moderador le sirve ver "esto parece un teléfono" señalado.
	Avisos []Aviso `json:"avisos"`
}

// Reporte es una denuncia viva sobre una historia.
type Reporte struct {
	Motivo    string    `json:"motivo"`
	Detalle   string    `json:"detalle"`
	CreatedAt time.Time `json:"createdAt"`
}

// Cola devuelve las historias en revisión, la más vieja primero: quien lleva más
// tiempo escondida es la que más urge mirar, sea para devolverla o para
// retirarla de verdad.
func (s *Store) Cola(ctx context.Context, limit int) ([]EnCola, error) {
	rows, err := s.db.Query(ctx, `
		SELECT cs.id::text, cs.author_id::text, cs.alias_snapshot,
		       cs.titulo, cs.cuerpo, cs.objetivo, cs.created_at,
		       (SELECT count(*) FROM moderation_actions ma
		         JOIN community_stories prev ON prev.id = ma.story_id
		        WHERE prev.author_id = cs.author_id AND ma.accion = 'RETIRADA')
		FROM community_stories cs
		WHERE cs.estado = 'EN_REVISION'
		ORDER BY cs.created_at ASC
		LIMIT $1`, limit)
	if err != nil {
		return nil, err
	}
	defer rows.Close()

	lista := []EnCola{}
	ids := []string{}
	for rows.Next() {
		var e EnCola
		if err := rows.Scan(&e.ID, &e.AutorID, &e.Alias, &e.Titulo, &e.Cuerpo,
			&e.Objetivo, &e.CreatedAt, &e.RetiradasPrevias); err != nil {
			return nil, err
		}
		e.Reportes = []Reporte{}
		e.Avisos = Avisos(e.Titulo, e.Cuerpo, e.Objetivo)
		lista = append(lista, e)
		ids = append(ids, e.ID)
	}
	if err := rows.Err(); err != nil {
		return nil, err
	}
	if len(ids) == 0 {
		return lista, nil
	}

	// Los reportes en una sola consulta para todas las historias: la cola es
	// corta, pero una consulta por fila es la clase de detalle que se convierte
	// en problema justo cuando hay mucho que moderar.
	repRows, err := s.db.Query(ctx, `
		SELECT story_id::text, motivo, detalle, created_at
		FROM community_reports
		WHERE story_id = ANY($1) AND resuelto_en IS NULL
		ORDER BY created_at ASC`, ids)
	if err != nil {
		return nil, err
	}
	defer repRows.Close()

	porHistoria := map[string][]Reporte{}
	for repRows.Next() {
		var storyID string
		var r Reporte
		if err := repRows.Scan(&storyID, &r.Motivo, &r.Detalle, &r.CreatedAt); err != nil {
			return nil, err
		}
		porHistoria[storyID] = append(porHistoria[storyID], r)
	}
	if err := repRows.Err(); err != nil {
		return nil, err
	}
	for i := range lista {
		if rs := porHistoria[lista[i].ID]; rs != nil {
			lista[i].Reportes = rs
		}
	}
	return lista, nil
}

// Aprobar devuelve la historia al muro: los reportes eran infundados.
//
// El contador vuelve a cero y los reportes quedan resueltos. Eso es lo que
// impide que la historia caiga otra vez sola con los mismos tres reportes de
// antes —sería devolverla para que desapareciera al instante— y a la vez
// devuelve a esas personas el derecho a reportarla de nuevo si el autor la
// empeora después.
func (s *Store) Aprobar(ctx context.Context, moderadorID, storyID, motivo string) error {
	return s.resolver(ctx, moderadorID, storyID, motivo, Publicada, "APROBADA")
}

// Retirar la saca del muro para siempre. No borra la fila: el autor tiene que
// poder ver que su historia se retiró, y la auditoría necesita a qué apuntar.
func (s *Store) Retirar(ctx context.Context, moderadorID, storyID, motivo string) error {
	return s.resolver(ctx, moderadorID, storyID, motivo, Retirada, "RETIRADA")
}

// resolver es el tronco común: cambia el estado, resuelve los reportes vivos y
// deja constancia, todo en la misma transacción. Si la auditoría fallara después
// de cambiar el estado, tendríamos una historia moderada sin registro de quién
// lo hizo, que es exactamente lo que la auditoría existe para evitar.
func (s *Store) resolver(ctx context.Context, moderadorID, storyID, motivo, estado, accion string) error {
	tx, err := s.db.Begin(ctx)
	if err != nil {
		return err
	}
	defer tx.Rollback(ctx) //nolint:errcheck // no-op tras commit

	var actual string
	err = tx.QueryRow(ctx,
		`SELECT estado::text FROM community_stories WHERE id = $1`, storyID).Scan(&actual)
	if errors.Is(err, pgx.ErrNoRows) || isBadUUID(err) {
		return ErrNoEncontrada
	}
	if err != nil {
		return err
	}
	// Solo se resuelve lo que está en la cola. Sin esto, dos moderadores con la
	// lista abierta a la vez actuarían dos veces sobre la misma historia y la
	// segunda decisión pisaría a la primera en silencio.
	if actual != EnRevision {
		return ErrNoEnRevision
	}

	if _, err := tx.Exec(ctx, `
		UPDATE community_stories
		SET estado = $2::story_state, reports_count = 0
		WHERE id = $1`, storyID, estado); err != nil {
		return err
	}
	if _, err := tx.Exec(ctx, `
		UPDATE community_reports SET resuelto_en = now()
		WHERE story_id = $1 AND resuelto_en IS NULL`, storyID); err != nil {
		return err
	}
	if _, err := tx.Exec(ctx, `
		INSERT INTO moderation_actions (story_id, moderator_id, accion, motivo)
		VALUES ($1, $2, $3::moderation_action, $4)`,
		storyID, moderadorID, accion, motivo); err != nil {
		return err
	}
	return tx.Commit(ctx)
}
