// Package stats cierra el hallazgo #7: aggregateRiskTrends estaba implementada
// y testeada en el backend anterior, pero ninguna función la exportaba. Era
// código muerto y la Fase 2·06 quedaba sin cerrar.
//
// La agregación se hace en SQL, no trayéndose los check-ins al proceso: es la
// misma corrección del hallazgo #14 aplicada a la lectura más pesada de todas.
// Lo único que se calcula en Go es la tendencia, porque es una decisión de
// producto —qué cuenta como "mejorando"— y conviene tenerla pura y testeable
// sin base de datos.
package stats

import (
	"context"
	"net/http"
	"time"

	"github.com/jackc/pgx/v5/pgxpool"

	"github.com/oderflaY/Bakend-un-dia-mas/internal/auth"
	"github.com/oderflaY/Bakend-un-dia-mas/internal/httpx"
	"github.com/oderflaY/Bakend-un-dia-mas/internal/risk"
)

// defaultTZ: el proyecto es mexicano y el corte del día tiene que coincidir con
// el día que vivió el usuario, no con UTC. La app puede mandar otra en ?tz=.
const defaultTZ = "America/Mexico_City"

const (
	defaultDays = 30
	maxDays     = 365
	topN        = 5
)

// Tendencias posibles. Son códigos estables: la app hace switch sobre ellos.
const (
	TrendImproving = "mejorando"
	TrendStable    = "estable"
	TrendWorsening = "empeorando"
	TrendNoData    = "sin-datos"
)

type Day struct {
	Fecha           string     `json:"fecha"` // YYYY-MM-DD en la zona pedida
	CheckIns        int        `json:"checkIns"`
	PromedioCraving float64    `json:"promedioCraving"`
	PeorNivel       risk.Level `json:"peorNivel"`
	Verdes          int        `json:"verdes"`
	Amarillos       int        `json:"amarillos"`
	Rojos           int        `json:"rojos"`
}

type Count struct {
	Valor string `json:"valor"`
	Veces int    `json:"veces"`
}

type Report struct {
	Dias            int       `json:"dias"`
	Desde           time.Time `json:"desde"`
	Hasta           time.Time `json:"hasta"`
	Zona            string    `json:"zona"`
	TotalCheckIns   int       `json:"totalCheckIns"`
	Verdes          int       `json:"verdes"`
	Amarillos       int       `json:"amarillos"`
	Rojos           int       `json:"rojos"`
	PromedioCraving float64   `json:"promedioCraving"`
	Detonantes      []Count   `json:"detonantesFrecuentes"`
	Animos          []Count   `json:"animosFrecuentes"`
	Recaidas        int       `json:"recaidas"`
	Alertas         int       `json:"alertas"`
	SerieDiaria     []Day     `json:"serieDiaria"`
	Tendencia       string    `json:"tendencia"`
}

type Store struct{ db *pgxpool.Pool }

func NewStore(db *pgxpool.Pool) *Store { return &Store{db: db} }

// RiskTrends arma el informe completo. Cinco consultas cortas y acotadas por
// user_id y por ventana temporal; ninguna puede crecer sin límite.
func (s *Store) RiskTrends(ctx context.Context, userID string, days int, tz string) (Report, error) {
	rep := Report{
		Dias:        days,
		Hasta:       time.Now(),
		Zona:        tz,
		Detonantes:  []Count{},
		Animos:      []Count{},
		SerieDiaria: []Day{},
	}
	rep.Desde = rep.Hasta.AddDate(0, 0, -days)

	rows, err := s.db.Query(ctx, `
		SELECT (created_at AT TIME ZONE $3)::date AS dia,
		       count(*),
		       coalesce(avg(craving_level), 0),
		       count(*) FILTER (WHERE risk_level = 'green'),
		       count(*) FILTER (WHERE risk_level = 'yellow'),
		       count(*) FILTER (WHERE risk_level = 'red')
		FROM check_ins
		WHERE user_id = $1 AND created_at >= now() - make_interval(days => $2)
		GROUP BY dia
		ORDER BY dia ASC`, userID, days, tz)
	if err != nil {
		return Report{}, err
	}
	defer rows.Close()

	var cravingSum float64
	for rows.Next() {
		var d Day
		var fecha time.Time
		if err := rows.Scan(&fecha, &d.CheckIns, &d.PromedioCraving,
			&d.Verdes, &d.Amarillos, &d.Rojos); err != nil {
			return Report{}, err
		}
		d.Fecha = fecha.Format(time.DateOnly)
		d.PromedioCraving = round1(d.PromedioCraving)
		d.PeorNivel = worst(d)

		cravingSum += d.PromedioCraving * float64(d.CheckIns)
		rep.TotalCheckIns += d.CheckIns
		rep.Verdes += d.Verdes
		rep.Amarillos += d.Amarillos
		rep.Rojos += d.Rojos
		rep.SerieDiaria = append(rep.SerieDiaria, d)
	}
	if err := rows.Err(); err != nil {
		return Report{}, err
	}
	if rep.TotalCheckIns > 0 {
		rep.PromedioCraving = round1(cravingSum / float64(rep.TotalCheckIns))
	}

	// unnest expande el array de detonantes: contar en SQL evita traerse todos
	// los check-ins solo para agrupar strings en memoria.
	if rep.Detonantes, err = s.counts(ctx, `
		SELECT t, count(*) AS veces
		FROM check_ins, unnest(triggers) AS t
		WHERE user_id = $1 AND created_at >= now() - make_interval(days => $2)
		  AND t <> ''
		GROUP BY t ORDER BY veces DESC, t ASC LIMIT $3`, userID, days); err != nil {
		return Report{}, err
	}

	if rep.Animos, err = s.counts(ctx, `
		SELECT mood, count(*) AS veces
		FROM mood_logs
		WHERE user_id = $1 AND created_at >= now() - make_interval(days => $2)
		GROUP BY mood ORDER BY veces DESC, mood ASC LIMIT $3`, userID, days); err != nil {
		return Report{}, err
	}

	if err := s.db.QueryRow(ctx, `
		SELECT
			(SELECT count(*) FROM relapse_events
			  WHERE user_id = $1 AND created_at >= now() - make_interval(days => $2)),
			(SELECT count(*) FROM alerts
			  WHERE user_id = $1 AND created_at >= now() - make_interval(days => $2))`,
		userID, days).Scan(&rep.Recaidas, &rep.Alertas); err != nil {
		return Report{}, err
	}

	rep.Tendencia = Trend(rep.SerieDiaria)
	return rep, nil
}

func (s *Store) counts(ctx context.Context, query, userID string, days int) ([]Count, error) {
	rows, err := s.db.Query(ctx, query, userID, days, topN)
	if err != nil {
		return nil, err
	}
	defer rows.Close()

	out := []Count{}
	for rows.Next() {
		var c Count
		if err := rows.Scan(&c.Valor, &c.Veces); err != nil {
			return nil, err
		}
		out = append(out, c)
	}
	return out, rows.Err()
}

// Trend compara la primera mitad del periodo con la segunda. Es deliberadamente
// tosco: con datos de un mes y un check-in al día, cualquier cosa más fina
// convierte el ruido en un diagnóstico.
//
// Solo cuentan los días con check-in. Un día sin registrar no es un día bueno.
func Trend(series []Day) string {
	if len(series) < 4 {
		return TrendNoData
	}
	mid := len(series) / 2
	before, ok1 := meanScore(series[:mid])
	after, ok2 := meanScore(series[mid:])
	if !ok1 || !ok2 {
		return TrendNoData
	}

	// Un cuarto de nivel de semáforo: menos que eso es ruido.
	const umbral = 0.25
	switch {
	case after < before-umbral:
		return TrendImproving
	case after > before+umbral:
		return TrendWorsening
	default:
		return TrendStable
	}
}

// meanScore promedia el riesgo del tramo ponderando por número de check-ins:
// un día con cinco rojos pesa más que uno con un verde suelto.
func meanScore(days []Day) (float64, bool) {
	var sum, n float64
	for _, d := range days {
		sum += float64(d.Verdes)*0 + float64(d.Amarillos)*1 + float64(d.Rojos)*2
		n += float64(d.CheckIns)
	}
	if n == 0 {
		return 0, false
	}
	return sum / n, true
}

func worst(d Day) risk.Level {
	switch {
	case d.Rojos > 0:
		return risk.Red
	case d.Amarillos > 0:
		return risk.Yellow
	default:
		return risk.Green
	}
}

func round1(v float64) float64 { return float64(int(v*10+0.5)) / 10 }

type Handler struct {
	store  *Store
	issuer *auth.TokenIssuer
}

func NewHandler(store *Store, issuer *auth.TokenIssuer) *Handler {
	return &Handler{store: store, issuer: issuer}
}

func (h *Handler) Routes(mux *http.ServeMux) {
	mux.Handle("GET /v1/stats/risk-trends", h.issuer.Middleware(http.HandlerFunc(h.riskTrends)))
}

func (h *Handler) riskTrends(w http.ResponseWriter, r *http.Request) {
	id := auth.MustFrom(r.Context())

	days := clampDays(r.URL.Query().Get("days"))

	tz := defaultTZ
	if v := r.URL.Query().Get("tz"); v != "" {
		// Se valida en Go antes de que llegue a la consulta: una zona inventada
		// haría fallar el AT TIME ZONE con un 500 que en realidad es un 400.
		if _, err := time.LoadLocation(v); err != nil {
			httpx.Error(w, http.StatusBadRequest, "invalid-argument", "zona horaria desconocida")
			return
		}
		tz = v
	}

	rep, err := h.store.RiskTrends(r.Context(), id.UserID, days, tz)
	if err != nil {
		httpx.Error(w, http.StatusInternalServerError, "internal", "no se pudieron calcular las estadísticas")
		return
	}
	httpx.JSON(w, http.StatusOK, rep)
}

// clampDays acota ?days= a [1, maxDays]. Sin tope, un cliente podría pedir la
// agregación de diez años en cada refresco de pantalla.
func clampDays(v string) int {
	if v == "" {
		return defaultDays
	}
	var n int
	for _, c := range v {
		if c < '0' || c > '9' {
			return defaultDays
		}
		n = n*10 + int(c-'0')
		if n > maxDays {
			return maxDays
		}
	}
	if n < 1 {
		return defaultDays
	}
	return n
}
