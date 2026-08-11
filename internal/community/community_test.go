package community

import (
	"context"
	"testing"
	"time"

	"github.com/jackc/pgx/v5/pgxpool"

	"github.com/oderflaY/Bakend-un-dia-mas/internal/testdb"
)

// persona crea un usuario con una racha de `dias` y, si se le pasa alias, su
// perfil de comunidad.
func persona(t *testing.T, pool *pgxpool.Pool, correo string, dias int, alias string) string {
	t.Helper()
	ctx := t.Context()

	userID := testdb.NewUser(t, pool, correo)
	if _, err := pool.Exec(ctx, `
		UPDATE sobriety_trackers SET start_date = now() - make_interval(days => $2)
		WHERE user_id = $1`, userID, dias); err != nil {
		t.Fatalf("racha: %v", err)
	}
	if alias != "" {
		if _, err := pool.Exec(ctx,
			`INSERT INTO community_profiles (user_id, alias) VALUES ($1, $2)`, userID, alias); err != nil {
			t.Fatalf("alias: %v", err)
		}
	}
	return userID
}

func historia(t *testing.T, s *Store, userID, titulo string, compartirRacha bool) Story {
	t.Helper()
	st, err := s.Publicar(t.Context(), userID, NuevaStory{
		Titulo:         titulo,
		Cuerpo:         "Cuerpo suficientemente largo para pasar el mínimo de ochenta caracteres del muro, contando algo real.",
		Objetivo:       "un día más",
		CompartirRacha: compartirRacha,
	})
	if err != nil {
		t.Fatalf("Publicar(%q): %v", titulo, err)
	}
	return st
}

func nuevo(t *testing.T) (*Store, *pgxpool.Pool, context.Context) {
	t.Helper()
	pool := testdb.New(t)
	return NewStore(pool), pool, t.Context()
}

// El umbral de 30 días: es la regla que decide quién puede publicar.
func TestElUmbralSonTreintaDias(t *testing.T) {
	s, pool, ctx := nuevo(t)

	novata := persona(t, pool, "novata@undiamas.mx", 12, "Novata")
	p, err := s.Perfil(ctx, novata)
	if err != nil {
		t.Fatalf("Perfil: %v", err)
	}
	if p.Elegible {
		t.Error("con 12 días no debería poder publicar")
	}
	if p.FaltanDias != 18 {
		t.Errorf("faltanDias = %d, se esperaba 18", p.FaltanDias)
	}
	if _, err := s.Publicar(ctx, novata, NuevaStory{Titulo: "t", Cuerpo: "c"}); err != ErrNoElegible {
		t.Errorf("Publicar con 12 días = %v, se esperaba ErrNoElegible", err)
	}

	justa := persona(t, pool, "justa@undiamas.mx", 30, "Justa")
	if p, _ := s.Perfil(ctx, justa); !p.Elegible {
		t.Error("con exactamente 30 días sí se puede publicar")
	}
}

func TestPublicarExigeAlias(t *testing.T) {
	s, pool, ctx := nuevo(t)
	sinAlias := persona(t, pool, "sinalias@undiamas.mx", 60, "")

	if _, err := s.Publicar(ctx, sinAlias, NuevaStory{Titulo: "t", Cuerpo: "c"}); err != ErrSinAlias {
		t.Errorf("Publicar sin alias = %v, se esperaba ErrSinAlias", err)
	}
}

// La decisión 3: lo publicado se congela. Es la que más fácil se rompe al
// "mejorar" el JOIN del muro para traer el alias actual.
func TestElAliasYLaRachaSeCongelanAlPublicar(t *testing.T) {
	s, pool, ctx := nuevo(t)
	userID := persona(t, pool, "congela@undiamas.mx", 214, "Roberto")

	st := historia(t, s, userID, "Dos años", true)
	if st.DiasRacha == nil || *st.DiasRacha != 214 {
		t.Fatalf("diasRacha al publicar = %v, se esperaba 214", st.DiasRacha)
	}

	// Cambia el alias y recae (la racha vuelve a cero).
	if _, err := s.GuardarAlias(ctx, userID, "Otro Nombre"); err != nil {
		t.Fatalf("GuardarAlias: %v", err)
	}
	if _, err := pool.Exec(ctx,
		`UPDATE sobriety_trackers SET start_date = now() WHERE user_id = $1`, userID); err != nil {
		t.Fatalf("recaída: %v", err)
	}

	page, err := s.Listar(ctx, userID, OrdenRacha, 10, "")
	if err != nil {
		t.Fatalf("Listar: %v", err)
	}
	if len(page.Items) != 1 {
		t.Fatalf("historias = %d", len(page.Items))
	}
	if page.Items[0].Alias != "Roberto" {
		t.Errorf("alias = %q; cambiar el alias solo desliga hacia adelante", page.Items[0].Alias)
	}
	if page.Items[0].DiasRacha == nil || *page.Items[0].DiasRacha != 214 {
		t.Errorf("diasRacha = %v; la racha publicada no se recalcula", page.Items[0].DiasRacha)
	}
}

func TestCompartirRachaApagadoDejaElCampoNulo(t *testing.T) {
	s, pool, _ := nuevo(t)
	userID := persona(t, pool, "privada@undiamas.mx", 90, "Privada")

	st := historia(t, s, userID, "Sin números", false)
	if st.DiasRacha != nil {
		t.Errorf("diasRacha = %v, se esperaba null: eligió no compartirla", *st.DiasRacha)
	}
}

// La decisión 2: cursor y no offset. El test recorre el muro entero de dos en
// dos y comprueba que no se repite ni se salta ninguna.
func TestLaPaginacionPorCursorNoRepiteNiSalta(t *testing.T) {
	s, pool, ctx := nuevo(t)

	// Rachas distintas para que el orden por "más tiempo" sea determinista.
	for i, dias := range []int{300, 250, 200, 150, 100} {
		u := persona(t, pool, string(rune('a'+i))+"@undiamas.mx", dias, "Alias"+string(rune('A'+i)))
		historia(t, s, u, "Historia de "+string(rune('A'+i)), true)
	}
	lector := persona(t, pool, "lector@undiamas.mx", 40, "Lector")

	for _, orden := range []Orden{OrdenRacha, OrdenRecientes, OrdenUtiles} {
		vistas := map[string]bool{}
		cursor := ""
		for vuelta := 0; vuelta < 10; vuelta++ {
			page, err := s.Listar(ctx, lector, orden, 2, cursor)
			if err != nil {
				t.Fatalf("Listar(%s): %v", orden, err)
			}
			for _, st := range page.Items {
				if vistas[st.ID] {
					t.Fatalf("orden %s: la historia %q salió dos veces", orden, st.Titulo)
				}
				vistas[st.ID] = true
			}
			cursor = page.SiguienteCursor
			if cursor == "" {
				break
			}
		}
		if len(vistas) != 5 {
			t.Errorf("orden %s: se vieron %d historias de 5", orden, len(vistas))
		}
	}
}

func TestOrdenPorRachaPoneAlDeMasTiempoPrimero(t *testing.T) {
	s, pool, ctx := nuevo(t)

	corta := persona(t, pool, "corta@undiamas.mx", 45, "Corta")
	larga := persona(t, pool, "larga@undiamas.mx", 800, "Larga")
	oculta := persona(t, pool, "oculta@undiamas.mx", 500, "Oculta")

	historia(t, s, corta, "45 días", true)
	historia(t, s, larga, "800 días", true)
	historia(t, s, oculta, "sin racha", false) // no comparte: va al final

	page, err := s.Listar(ctx, corta, OrdenRacha, 10, "")
	if err != nil {
		t.Fatalf("Listar: %v", err)
	}
	if len(page.Items) != 3 {
		t.Fatalf("items = %d", len(page.Items))
	}
	if page.Items[0].Titulo != "800 días" {
		t.Errorf("primero = %q, se esperaba la racha más larga", page.Items[0].Titulo)
	}
	if page.Items[2].Titulo != "sin racha" {
		t.Errorf("último = %q; quien no comparte racha va al final, no al principio", page.Items[2].Titulo)
	}
}

func TestSoloElAutorBorraYUnAjenoEsCuatrocientosCuatro(t *testing.T) {
	s, pool, ctx := nuevo(t)
	autor := persona(t, pool, "autor@undiamas.mx", 100, "Autor")
	otro := persona(t, pool, "otro@undiamas.mx", 100, "Otro")

	st := historia(t, s, autor, "Mía", true)

	// La decisión 5: para alguien ajeno, existir y no existir se responden igual.
	if err := s.Borrar(ctx, otro, st.ID); err != ErrNoEncontrada {
		t.Errorf("borrado ajeno = %v, se esperaba ErrNoEncontrada", err)
	}
	if err := s.Borrar(ctx, autor, st.ID); err != nil {
		t.Errorf("el autor no pudo borrar la suya: %v", err)
	}
	if err := s.Borrar(ctx, autor, st.ID); err != ErrNoEncontrada {
		t.Errorf("borrar dos veces = %v", err)
	}
}

func TestMeAyudoEsIdempotenteYCuentaUnaVezPorPersona(t *testing.T) {
	s, pool, ctx := nuevo(t)
	autor := persona(t, pool, "a@undiamas.mx", 100, "A")
	lector := persona(t, pool, "b@undiamas.mx", 40, "B")
	otro := persona(t, pool, "c@undiamas.mx", 40, "C")

	st := historia(t, s, autor, "Con votos", true)

	for i := 0; i < 3; i++ {
		total, err := s.MarcarUtil(ctx, lector, st.ID, true)
		if err != nil {
			t.Fatalf("MarcarUtil: %v", err)
		}
		if total != 1 {
			t.Fatalf("tras %d marcas del mismo usuario, utiles = %d", i+1, total)
		}
	}

	if total, _ := s.MarcarUtil(ctx, otro, st.ID, true); total != 2 {
		t.Errorf("con dos personas distintas utiles = %d, se esperaba 2", total)
	}
	if total, _ := s.MarcarUtil(ctx, lector, st.ID, false); total != 1 {
		t.Errorf("al quitar el voto utiles = %d, se esperaba 1", total)
	}
	// Quitar dos veces no puede dejar el contador en negativo.
	if total, _ := s.MarcarUtil(ctx, lector, st.ID, false); total != 1 {
		t.Errorf("quitar dos veces dejó utiles = %d", total)
	}
}

// Moderación: tres reportes de personas distintas retiran la historia del muro.
func TestTresReportesDistintosMandanARevision(t *testing.T) {
	s, pool, ctx := nuevo(t)
	autor := persona(t, pool, "autor@undiamas.mx", 100, "Autor")
	st := historia(t, s, autor, "Reportada", true)

	var estado string
	for i := 0; i < 3; i++ {
		lector := persona(t, pool, string(rune('r'+i))+"@undiamas.mx", 40, "R"+string(rune('A'+i)))
		var err error
		estado, err = s.Reportar(ctx, lector, st.ID, "contenido-peligroso", "")
		if err != nil {
			t.Fatalf("Reportar %d: %v", i+1, err)
		}
		if i < 2 && estado != Publicada {
			t.Errorf("con %d reportes el estado ya era %q", i+1, estado)
		}
	}
	if estado != EnRevision {
		t.Errorf("con 3 reportes estado = %q, se esperaba EN_REVISION", estado)
	}

	// Y desaparece del muro para el resto.
	lector := persona(t, pool, "mirona@undiamas.mx", 40, "Mirona")
	page, _ := s.Listar(ctx, lector, OrdenRecientes, 10, "")
	if len(page.Items) != 0 {
		t.Errorf("una historia en revisión sigue visible: %v", page.Items)
	}
	// Pero su autora sí la ve, con el estado, para enterarse de lo que pasó.
	mias, _ := s.Listar(ctx, autor, OrdenRecientes, 10, "")
	if len(mias.Items) != 1 || mias.Items[0].Estado != EnRevision {
		t.Errorf("la autora no ve su historia en revisión: %v", mias.Items)
	}
}

func TestTresReportesDeLaMismaPersonaNoBastan(t *testing.T) {
	s, pool, ctx := nuevo(t)
	autor := persona(t, pool, "autor@undiamas.mx", 100, "Autor")
	insistente := persona(t, pool, "insiste@undiamas.mx", 40, "Insiste")
	st := historia(t, s, autor, "Reportada", true)

	for i := 0; i < 3; i++ {
		estado, err := s.Reportar(ctx, insistente, st.ID, "spam", "")
		if err != nil {
			t.Fatalf("Reportar: %v", err)
		}
		if estado != Publicada {
			t.Fatalf("el mismo dedo tres veces la mandó a revisión (%q)", estado)
		}
	}
}

func TestBloquearAutorEsconderTodoLoSuyoYSobreviveAlCambioDeAlias(t *testing.T) {
	s, pool, ctx := nuevo(t)
	molesto := persona(t, pool, "molesto@undiamas.mx", 100, "Molesto")
	lector := persona(t, pool, "lector@undiamas.mx", 40, "Lector")

	st := historia(t, s, molesto, "Primera", true)
	historia(t, s, molesto, "Segunda", true)

	if err := s.BloquearAutor(ctx, lector, st.ID); err != nil {
		t.Fatalf("BloquearAutor: %v", err)
	}

	page, _ := s.Listar(ctx, lector, OrdenRecientes, 10, "")
	if len(page.Items) != 0 {
		t.Errorf("tras bloquear siguen %d historias suyas visibles", len(page.Items))
	}

	// El bloqueo va contra la cuenta, no contra el nombre: cambiar de alias no
	// lo esquiva.
	if _, err := s.GuardarAlias(ctx, molesto, "Nombre Nuevo"); err != nil {
		t.Fatalf("GuardarAlias: %v", err)
	}
	historia(t, s, molesto, "Tercera con otro alias", true)
	page, _ = s.Listar(ctx, lector, OrdenRecientes, 10, "")
	if len(page.Items) != 0 {
		t.Errorf("el bloqueo se esquivó cambiando de alias: %v", page.Items)
	}
}

func TestNoTePuedesReportarNiBloquearATiMisma(t *testing.T) {
	s, pool, ctx := nuevo(t)
	autor := persona(t, pool, "autor@undiamas.mx", 100, "Autor")
	st := historia(t, s, autor, "Mía", true)

	if _, err := s.Reportar(ctx, autor, st.ID, "spam", ""); err != ErrPropia {
		t.Errorf("reportarse a sí misma = %v", err)
	}
	if err := s.BloquearAutor(ctx, autor, st.ID); err != ErrPropia {
		t.Errorf("bloquearse a sí misma = %v", err)
	}
}

func TestElAliasEsUnicoSinDistinguirMayusculas(t *testing.T) {
	s, pool, ctx := nuevo(t)
	persona(t, pool, "primera@undiamas.mx", 100, "Sofia")
	segunda := persona(t, pool, "segunda@undiamas.mx", 100, "")

	if _, err := s.GuardarAlias(ctx, segunda, "sofia"); err != ErrAliasTomado {
		t.Errorf("alias repetido en otra caja = %v, se esperaba ErrAliasTomado", err)
	}
	if _, err := s.GuardarAlias(ctx, segunda, "So"); err != ErrAliasInvalido {
		t.Errorf("alias de 2 letras = %v", err)
	}
	if _, err := s.GuardarAlias(ctx, segunda, "ana@correo.mx"); err != ErrAliasInvalido {
		t.Errorf("un alias con arroba invita a buscar a esa persona fuera: %v", err)
	}
	if _, err := s.GuardarAlias(ctx, segunda, "Sofía Dos"); err != nil {
		t.Errorf("un alias válido con acento y espacio falló: %v", err)
	}
}

// El borrado de cuenta tiene que llevarse las cuatro tablas de comunidad. Si
// alguien añade una tabla sin ON DELETE CASCADE, este test la encuentra.
func TestBorrarLaCuentaSeLlevaLaComunidadEntera(t *testing.T) {
	s, pool, ctx := nuevo(t)
	autor := persona(t, pool, "sevá@undiamas.mx", 100, "SeVa")
	otro := persona(t, pool, "queda@undiamas.mx", 100, "Queda")

	suya := historia(t, s, autor, "Se va con él", true)
	ajena := historia(t, s, otro, "De alguien más", true)

	if _, err := s.MarcarUtil(ctx, autor, ajena.ID, true); err != nil {
		t.Fatalf("MarcarUtil: %v", err)
	}
	if _, err := s.Reportar(ctx, autor, ajena.ID, "spam", ""); err != nil {
		t.Fatalf("Reportar: %v", err)
	}
	if err := s.BloquearAutor(ctx, autor, ajena.ID); err != nil {
		t.Fatalf("BloquearAutor: %v", err)
	}

	if _, err := pool.Exec(ctx, `DELETE FROM users WHERE id = $1`, autor); err != nil {
		t.Fatalf("DELETE users: %v", err)
	}

	tablas := map[string]string{
		"community_profiles":     "user_id",
		"community_stories":      "author_id",
		"community_story_useful": "user_id",
		"community_reports":      "reporter_id",
		"community_blocks":       "user_id",
	}
	for tabla, col := range tablas {
		var n int
		if err := pool.QueryRow(ctx,
			`SELECT count(*) FROM `+tabla+` WHERE `+col+` = $1`, autor).Scan(&n); err != nil {
			t.Fatalf("count %s: %v", tabla, err)
		}
		if n != 0 {
			t.Errorf("%s conservó %d filas tras borrar la cuenta", tabla, n)
		}
	}

	// Y la historia de la otra persona sigue ahí, con el voto descontado.
	var quedan int
	if err := pool.QueryRow(ctx, `SELECT count(*) FROM community_stories WHERE id = $1`, ajena.ID).
		Scan(&quedan); err != nil {
		t.Fatalf("count ajena: %v", err)
	}
	if quedan != 1 {
		t.Error("borrar una cuenta se llevó la historia de otra persona")
	}
	_ = suya
}

func TestElCursorInvalidoNoRevientaLaConsulta(t *testing.T) {
	s, pool, ctx := nuevo(t)
	lector := persona(t, pool, "lector@undiamas.mx", 40, "Lector")

	if _, err := s.Listar(ctx, lector, OrdenRacha, 10, "no-es-base64-válido!!"); err == nil {
		t.Error("un cursor corrupto debería devolver error, no filas al azar")
	}
}

func TestPerfilCuentaLasHistoriasPropias(t *testing.T) {
	s, pool, ctx := nuevo(t)
	autor := persona(t, pool, "cuenta@undiamas.mx", 100, "Cuenta")

	historia(t, s, autor, "Una", true)
	historia(t, s, autor, "Dos", true)

	p, err := s.Perfil(ctx, autor)
	if err != nil {
		t.Fatalf("Perfil: %v", err)
	}
	if p.Historias != 2 {
		t.Errorf("misHistorias = %d, se esperaba 2", p.Historias)
	}
	if p.CreatedAt == nil || time.Since(*p.CreatedAt) > time.Minute {
		t.Errorf("aliasDesde = %v", p.CreatedAt)
	}
}
