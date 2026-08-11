// Comando seed: crea cuentas de demostración con datos realistas.
//
// Existe para poder abrir la app y ver algo: un muro vacío y un contador en cero
// no dejan probar nada. Es idempotente —vuelve a dejar las mismas cuentas en el
// mismo estado— así que se puede correr las veces que haga falta.
//
// No es para producción. Las contraseñas están en el código a propósito, porque
// el objetivo es poder entrar; por eso el comando se niega a correr contra una
// base que no sea local salvo que se le insista con --force.
package main

import (
	"context"
	"flag"
	"fmt"
	"log"
	"os"
	"strings"
	"time"

	"github.com/jackc/pgx/v5/pgxpool"
	"golang.org/x/crypto/bcrypt"

	"github.com/oderflaY/Bakend-un-dia-mas/internal/db"
)

type cuenta struct {
	Correo      string
	Password    string
	Nombre      string
	Alias       string
	DiasRacha   int
	Adicciones  []string
	Principal   string
	ConsumoAnos int
	Tratamiento bool
	PorQue      string
	AhorroDia   float64
	Historias   []historia
}

type historia struct {
	Titulo   string
	Cuerpo   string
	Objetivo string
	Racha    bool
	Utiles   int
}

// Dos perfiles opuestos a propósito: uno recién elegible y otro con años. El
// muro se lee distinto según quién eres, y con una sola cuenta eso no se ve.
var cuentas = []cuenta{
	{
		Correo:      "ana@undiamas.mx",
		Password:    "UnDiaMas2026",
		Nombre:      "Ana",
		Alias:       "Ana R",
		DiasRacha:   47,
		Adicciones:  []string{"alcohol"},
		Principal:   "alcohol",
		ConsumoAnos: 9,
		Tratamiento: false,
		PorQue:      "Por mis hijas, y por volver a dormir de corrido.",
		AhorroDia:   180,
		Historias: []historia{{
			Titulo: "Los viernes siguen siendo lo más difícil",
			Cuerpo: "Llevo mes y medio. Entre semana casi no lo pienso, pero llegan las siete " +
				"de la tarde del viernes y el cuerpo se me va solo a la tienda de la esquina. " +
				"Lo que me está funcionando es no llegar a mi casa a esa hora: salgo a caminar " +
				"cuarenta minutos y cuando vuelvo ya pasó lo peor. No es fuerza de voluntad, " +
				"es no estar ahí cuando pega.",
			Objetivo: "llegar a los 60 días",
			Racha:    true,
			Utiles:   2,
		}},
	},
	{
		Correo:      "roberto@undiamas.mx",
		Password:    "UnDiaMas2026",
		Nombre:      "Roberto",
		Alias:       "Beto",
		DiasRacha:   1310, // tres años y medio
		Adicciones:  []string{"alcohol", "tabaco"},
		Principal:   "alcohol",
		ConsumoAnos: 22,
		Tratamiento: true,
		PorQue:      "Ya no quiero volver a empezar de cero.",
		AhorroDia:   250,
		Historias: []historia{
			{
				Titulo: "Recaí a los dos años y no fue el final",
				Cuerpo: "Cuento esto porque a mí me habría servido leerlo. Llevaba dos años " +
					"limpio, me confié en una boda y tomé. Al día siguiente estaba convencido " +
					"de que había tirado todo a la basura y por eso seguí tomando una semana " +
					"más. Eso fue lo que hizo daño: no la copa, la idea de que ya daba igual. " +
					"Volví a contar desde cero y hoy llevo más que entonces. Los dos años que " +
					"había hecho no se borraron, seguían en mí.",
				Objetivo: "que nadie se rinda por un día",
				Racha:    true,
				Utiles:   5,
			},
			{
				Titulo: "Lo que le digo a quien lleva una semana",
				Cuerpo: "Que no se compare conmigo. Los años no son mérito, son tiempo. " +
					"La primera semana fue más difícil para mí de lo que es cualquier mes " +
					"ahora, y quien va en su día tres está haciendo algo más duro que yo. " +
					"Si estás ahí: come, duerme y díselo a alguien hoy mismo. Es todo lo que " +
					"hice yo y no se me ocurre nada mejor.",
				Objetivo: "",
				Racha:    false,
				Utiles:   3,
			},
		},
	},
}

func main() {
	force := flag.Bool("force", false, "permite sembrar en una base que no es local")
	flag.Parse()

	url := os.Getenv("DATABASE_URL")
	if url == "" {
		log.Fatal("falta DATABASE_URL")
	}
	// Una cuenta de demostración con contraseña conocida en una base real es una
	// puerta abierta, así que hay que pedirlo explícitamente.
	if !*force && !esLocal(url) {
		log.Fatal("DATABASE_URL no parece local; usa --force si de verdad quieres sembrar aquí")
	}

	ctx := context.Background()
	pool, err := db.Open(ctx, url)
	if err != nil {
		log.Fatal(err)
	}
	defer pool.Close()

	if err := db.Migrate(ctx, pool); err != nil {
		log.Fatal(err)
	}

	fmt.Println("Cuentas de demostración:")
	fmt.Println()
	for _, c := range cuentas {
		if err := sembrar(ctx, pool, c); err != nil {
			log.Fatalf("%s: %v", c.Correo, err)
		}
		fmt.Printf("  %-22s  %s\n", c.Correo, c.Password)
		fmt.Printf("  %-22s  %s · %d días de racha · %s\n\n",
			"", c.Nombre, c.DiasRacha, strings.Join(c.Adicciones, ", "))
	}
	fmt.Println("Las dos pueden publicar en el muro (el umbral son 30 días).")
}

func sembrar(ctx context.Context, pool *pgxpool.Pool, c cuenta) error {
	hash, err := bcrypt.GenerateFromPassword([]byte(c.Password), bcrypt.DefaultCost)
	if err != nil {
		return err
	}
	consumoDesde := time.Now().AddDate(-c.ConsumoAnos, 0, 0)

	tx, err := pool.Begin(ctx)
	if err != nil {
		return err
	}
	defer tx.Rollback(ctx) //nolint:errcheck // no-op tras commit

	// Idempotencia por la vía corta: si ya existe, se borra y se vuelve a crear.
	// La cascada del esquema se lleva historias, votos y bloqueos, así que no
	// quedan restos de una siembra anterior.
	if _, err := tx.Exec(ctx, `DELETE FROM users WHERE email = lower($1)`, c.Correo); err != nil {
		return err
	}

	var userID string
	if err := tx.QueryRow(ctx, `
		INSERT INTO users (email, password_hash, display_name, por_que_personal,
		                   adicciones, adiccion_principal, consumo_desde, en_tratamiento)
		VALUES (lower($1), $2, $3, $4, $5, $6, $7, $8)
		RETURNING id`,
		c.Correo, string(hash), c.Nombre, c.PorQue,
		c.Adicciones, c.Principal, consumoDesde, c.Tratamiento).Scan(&userID); err != nil {
		return err
	}

	// El tracker: la racha sale de start_date, así que se coloca hacia atrás.
	if _, err := tx.Exec(ctx, `
		INSERT INTO sobriety_trackers (user_id, start_date, daily_savings_rate, currency)
		VALUES ($1, now() - make_interval(days => $2), $3, 'MXN')`,
		userID, c.DiasRacha, c.AhorroDia); err != nil {
		return err
	}

	// Contacto de confianza: sin él, un semáforo rojo llega sin a quién avisar.
	if _, err := tx.Exec(ctx, `
		INSERT INTO emergency_contacts (user_id, position, nombre, telefono, rol)
		VALUES ($1, 0, $2, '5555555555', 'familiar')`,
		userID, "Contacto de "+c.Nombre); err != nil {
		return err
	}

	if _, err := tx.Exec(ctx,
		`INSERT INTO community_profiles (user_id, alias) VALUES ($1, $2)`, userID, c.Alias); err != nil {
		return err
	}

	for i, h := range c.Historias {
		var dias *int
		if h.Racha {
			d := c.DiasRacha
			dias = &d
		}
		// Escalonadas hacia atrás para que el orden por "recientes" tenga sentido.
		if _, err := tx.Exec(ctx, `
			INSERT INTO community_stories
				(author_id, alias_snapshot, streak_days, titulo, cuerpo, objetivo,
				 useful_count, created_at)
			VALUES ($1, $2, $3, $4, $5, $6, $7, now() - make_interval(days => $8))`,
			userID, c.Alias, dias, h.Titulo, h.Cuerpo, h.Objetivo, h.Utiles, i+1); err != nil {
			return err
		}
	}

	// Un par de check-ins para que las estadísticas no salgan vacías.
	if _, err := tx.Exec(ctx, `
		INSERT INTO check_ins (user_id, risk_level, craving_level, mood, triggers, note, created_at)
		SELECT $1, 'green', 2, 'TRANQUILO', ARRAY['rutina'], '', now() - make_interval(days => g)
		FROM generate_series(0, 5) g`, userID); err != nil {
		return err
	}

	return tx.Commit(ctx)
}

func esLocal(url string) bool {
	return strings.Contains(url, "localhost") || strings.Contains(url, "127.0.0.1")
}
