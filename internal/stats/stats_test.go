package stats

import (
	"testing"

	"github.com/oderflaY/Bakend-un-dia-mas/internal/risk"
	"github.com/oderflaY/Bakend-un-dia-mas/internal/testdb"
)

// Trend es la única parte del informe que decide algo, así que se prueba sin
// base de datos: es lo que el backend anterior tenía bien (aggregateRiskTrends
// pura y testeada) y mal a la vez (nunca la llamó nadie).
func TestTrend(t *testing.T) {
	verde := func() Day { return Day{CheckIns: 1, Verdes: 1} }
	amarillo := func() Day { return Day{CheckIns: 1, Amarillos: 1} }
	rojo := func() Day { return Day{CheckIns: 1, Rojos: 1} }

	casos := []struct {
		nombre string
		serie  []Day
		quiere string
	}{
		{"sin datos suficientes", []Day{rojo(), verde()}, TrendNoData},
		{"de rojo a verde mejora", []Day{rojo(), rojo(), verde(), verde()}, TrendImproving},
		{"de verde a rojo empeora", []Day{verde(), verde(), rojo(), rojo()}, TrendWorsening},
		{"siempre igual es estable", []Day{amarillo(), amarillo(), amarillo(), amarillo()}, TrendStable},
		{
			// Un solo amarillo en la segunda mitad mueve la media 0.25 exactos:
			// justo el umbral, que no cuenta como empeorar.
			"ruido bajo el umbral",
			[]Day{verde(), verde(), verde(), verde(), verde(), verde(), verde(), amarillo()},
			TrendStable,
		},
		{
			// Un día con muchos rojos pesa más que uno con un verde suelto.
			"la ponderación cuenta check-ins, no días",
			[]Day{verde(), verde(), verde(), {CheckIns: 5, Rojos: 5}},
			TrendWorsening,
		},
	}

	for _, c := range casos {
		t.Run(c.nombre, func(t *testing.T) {
			if got := Trend(c.serie); got != c.quiere {
				t.Errorf("Trend = %q, se esperaba %q", got, c.quiere)
			}
		})
	}
}

func TestTrendSinCheckIns(t *testing.T) {
	// Cuatro días en la serie pero ninguno con check-ins: no hay nada que
	// promediar y la respuesta honesta es "sin datos", no "estable".
	vacios := []Day{{}, {}, {}, {}}
	if got := Trend(vacios); got != TrendNoData {
		t.Errorf("Trend = %q, se esperaba %q", got, TrendNoData)
	}
}

func TestClampDays(t *testing.T) {
	casos := map[string]int{
		"":       defaultDays,
		"7":      7,
		"0":      defaultDays,
		"99999":  maxDays,
		"abc":    defaultDays,
		"-1":     defaultDays,
		"30días": defaultDays,
	}
	for in, quiere := range casos {
		if got := clampDays(in); got != quiere {
			t.Errorf("clampDays(%q) = %d, se esperaba %d", in, got, quiere)
		}
	}
}

func TestRiskTrends(t *testing.T) {
	pool := testdb.New(t)
	ctx := t.Context()

	yo := testdb.NewUser(t, pool, "yo@undiamas.mx")
	otro := testdb.NewUser(t, pool, "otro@undiamas.mx")

	// Dos check-ins míos hace tres días y uno rojo hoy, más uno del otro usuario
	// que no debe aparecer en mi informe por ninguna vía.
	insert := func(userID, nivel string, craving, diasAtras int, triggers []string) {
		t.Helper()
		_, err := pool.Exec(ctx, `
			INSERT INTO check_ins (user_id, risk_level, craving_level, triggers, created_at)
			VALUES ($1, $2, $3, $4, now() - make_interval(days => $5))`,
			userID, nivel, craving, triggers, diasAtras)
		if err != nil {
			t.Fatalf("insert check-in: %v", err)
		}
	}
	insert(yo, "green", 2, 3, []string{"estrés"})
	insert(yo, "yellow", 6, 3, []string{"estrés", "fiesta"})
	insert(yo, "red", 9, 0, []string{"estrés"})
	insert(otro, "red", 10, 0, []string{"nada mío"})

	rep, err := NewStore(pool).RiskTrends(ctx, yo, 30, "America/Mexico_City")
	if err != nil {
		t.Fatalf("RiskTrends: %v", err)
	}

	if rep.TotalCheckIns != 3 {
		t.Errorf("totalCheckIns = %d, se esperaban 3 (los del otro usuario no cuentan)", rep.TotalCheckIns)
	}
	if rep.Verdes != 1 || rep.Amarillos != 1 || rep.Rojos != 1 {
		t.Errorf("reparto = %d/%d/%d, se esperaba 1/1/1", rep.Verdes, rep.Amarillos, rep.Rojos)
	}
	if len(rep.SerieDiaria) != 2 {
		t.Fatalf("serieDiaria = %d días, se esperaban 2", len(rep.SerieDiaria))
	}
	// La serie va de más antiguo a más reciente.
	if rep.SerieDiaria[0].PeorNivel != risk.Yellow {
		t.Errorf("peorNivel del día antiguo = %v, se esperaba AMARILLO", rep.SerieDiaria[0].PeorNivel)
	}
	if rep.SerieDiaria[1].PeorNivel != risk.Red {
		t.Errorf("peorNivel de hoy = %v, se esperaba ROJO", rep.SerieDiaria[1].PeorNivel)
	}
	if len(rep.Detonantes) == 0 || rep.Detonantes[0].Valor != "estrés" || rep.Detonantes[0].Veces != 3 {
		t.Errorf("detonantes = %+v, se esperaba estrés×3 en cabeza", rep.Detonantes)
	}

	// La ventana corta deja fuera los check-ins viejos.
	corto, err := NewStore(pool).RiskTrends(ctx, yo, 1, "America/Mexico_City")
	if err != nil {
		t.Fatalf("RiskTrends(1 día): %v", err)
	}
	if corto.TotalCheckIns != 1 {
		t.Errorf("con days=1 totalCheckIns = %d, se esperaba 1", corto.TotalCheckIns)
	}
}
