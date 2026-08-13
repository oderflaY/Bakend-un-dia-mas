package community

import (
	"errors"
	"testing"
)

// La cola enseña lo que hace falta para decidir: el texto, los reportes vivos y
// el historial del autor.
func TestLaColaTraeLoQueHaceFaltaParaDecidir(t *testing.T) {
	s, pool, ctx := nuevo(t)

	autor := persona(t, pool, "autor@undiamas.mx", 100, "Autor")
	st := historia(t, s, autor, "Historia señalada", true)

	for i, correo := range []string{"a@undiamas.mx", "b@undiamas.mx", "c@undiamas.mx"} {
		quien := persona(t, pool, correo, 40, "Rep"+string(rune('A'+i)))
		if _, err := s.Reportar(ctx, quien, st.ID, "acoso", "me hizo sentir mal"); err != nil {
			t.Fatalf("Reportar: %v", err)
		}
	}

	cola, err := s.Cola(ctx, 50)
	if err != nil {
		t.Fatalf("Cola: %v", err)
	}
	if len(cola) != 1 {
		t.Fatalf("la cola tiene %d historias, se esperaba 1", len(cola))
	}
	e := cola[0]
	if e.ID != st.ID {
		t.Errorf("id = %q, se esperaba %q", e.ID, st.ID)
	}
	if len(e.Reportes) != 3 {
		t.Errorf("reportes = %d, se esperaban 3", len(e.Reportes))
	}
	// El id del autor es justo lo que el resto del paquete esconde; aquí tiene
	// que venir, porque sin él no se puede ver un patrón.
	if e.AutorID != autor {
		t.Errorf("autorId = %q, se esperaba %q", e.AutorID, autor)
	}
	if e.RetiradasPrevias != 0 {
		t.Errorf("retiradasPrevias = %d en un autor sin historial", e.RetiradasPrevias)
	}
}

// Aprobar devuelve la historia al muro y no la deja caer otra vez con los mismos
// reportes: sin resolverlos, volvería a la cola al instante.
func TestAprobarDevuelveLaHistoriaYNoVuelveACaerSola(t *testing.T) {
	s, pool, ctx := nuevo(t)

	moderador := persona(t, pool, "mod@undiamas.mx", 0, "")
	autor := persona(t, pool, "injusta@undiamas.mx", 100, "Injusta")
	st := historia(t, s, autor, "La tumbaron entre tres", true)

	for i, correo := range []string{"x@undiamas.mx", "y@undiamas.mx", "z@undiamas.mx"} {
		quien := persona(t, pool, correo, 40, "Coord"+string(rune('A'+i)))
		if _, err := s.Reportar(ctx, quien, st.ID, "spam", ""); err != nil {
			t.Fatalf("Reportar: %v", err)
		}
	}

	if err := s.Aprobar(ctx, moderador, st.ID, "revisada, no infringe nada"); err != nil {
		t.Fatalf("Aprobar: %v", err)
	}

	var estado string
	var reportes int
	if err := pool.QueryRow(ctx,
		`SELECT estado::text, reports_count FROM community_stories WHERE id = $1`,
		st.ID).Scan(&estado, &reportes); err != nil {
		t.Fatalf("SELECT estado: %v", err)
	}
	if estado != Publicada {
		t.Errorf("estado = %q, se esperaba %q", estado, Publicada)
	}
	if reportes != 0 {
		t.Errorf("reports_count = %d tras aprobar, se esperaba 0", reportes)
	}

	cola, err := s.Cola(ctx, 50)
	if err != nil {
		t.Fatalf("Cola: %v", err)
	}
	if len(cola) != 0 {
		t.Errorf("la historia aprobada sigue en la cola")
	}

	// Y los reportes quedan como prueba, no se borran.
	var vivos, resueltos int
	if err := pool.QueryRow(ctx, `
		SELECT count(*) FILTER (WHERE resuelto_en IS NULL),
		       count(*) FILTER (WHERE resuelto_en IS NOT NULL)
		FROM community_reports WHERE story_id = $1`, st.ID).Scan(&vivos, &resueltos); err != nil {
		t.Fatalf("count reportes: %v", err)
	}
	if vivos != 0 || resueltos != 3 {
		t.Errorf("reportes vivos=%d resueltos=%d, se esperaba 0 y 3", vivos, resueltos)
	}
}

// Quien ya reportó puede volver a hacerlo después de que se resolviera. Es lo
// que la restricción de unicidad parcial permite y la anterior impedía.
func TestSePuedeVolverAReportarTrasUnaResolucion(t *testing.T) {
	s, pool, ctx := nuevo(t)

	autor := persona(t, pool, "reincide@undiamas.mx", 100, "Reincide")
	st := historia(t, s, autor, "Vuelve a las andadas", true)

	quien := persona(t, pool, "vigila@undiamas.mx", 40, "Vigila")
	if _, err := s.Reportar(ctx, quien, st.ID, "spam", "primera vez"); err != nil {
		t.Fatalf("primer Reportar: %v", err)
	}
	// Se resuelve a mano el reporte suelto: no hacen falta tres para probar esto.
	if _, err := pool.Exec(ctx,
		`UPDATE community_reports SET resuelto_en = now() WHERE story_id = $1`, st.ID); err != nil {
		t.Fatalf("resolver: %v", err)
	}

	if _, err := s.Reportar(ctx, quien, st.ID, "acoso", "segunda vez"); err != nil {
		t.Fatalf("segundo Reportar: %v", err)
	}
	var n int
	if err := pool.QueryRow(ctx,
		`SELECT count(*) FROM community_reports WHERE story_id = $1`, st.ID).Scan(&n); err != nil {
		t.Fatalf("count: %v", err)
	}
	if n != 2 {
		t.Errorf("reportes = %d, se esperaban 2 (uno resuelto y uno vivo)", n)
	}
}

// Dos moderadores con la lista abierta a la vez: el segundo se encuentra con que
// ya no hay nada que decidir, en vez de pisar la decisión del primero.
func TestNoSePuedeModerarDosVecesLaMismaHistoria(t *testing.T) {
	s, pool, ctx := nuevo(t)

	uno := persona(t, pool, "mod-uno@undiamas.mx", 0, "")
	dos := persona(t, pool, "mod-dos@undiamas.mx", 0, "")
	autor := persona(t, pool, "disputada@undiamas.mx", 100, "Disputada")
	st := historia(t, s, autor, "Historia disputada", true)

	for i, correo := range []string{"p@undiamas.mx", "q@undiamas.mx", "r@undiamas.mx"} {
		quien := persona(t, pool, correo, 40, "Rep"+string(rune('X'+i)))
		if _, err := s.Reportar(ctx, quien, st.ID, "otro", ""); err != nil {
			t.Fatalf("Reportar: %v", err)
		}
	}

	if err := s.Retirar(ctx, uno, st.ID, "contenido peligroso"); err != nil {
		t.Fatalf("Retirar: %v", err)
	}
	err := s.Aprobar(ctx, dos, st.ID, "a mí me parece bien")
	if !errors.Is(err, ErrNoEnRevision) {
		t.Errorf("Aprobar tras Retirar devolvió %v, se esperaba ErrNoEnRevision", err)
	}
}

// Retirar deja rastro de quién y por qué. Es lo que permite contestar "¿por qué
// se retiró mi historia?" tres meses después.
func TestRetirarDejaAuditoriaYCuentaEnElHistorialDelAutor(t *testing.T) {
	s, pool, ctx := nuevo(t)

	moderador := persona(t, pool, "mod3@undiamas.mx", 0, "")
	autor := persona(t, pool, "peligrosa@undiamas.mx", 100, "Peligrosa")
	primera := historia(t, s, autor, "Primera pasada de la raya", true)

	for i, correo := range []string{"m@undiamas.mx", "n@undiamas.mx", "o@undiamas.mx"} {
		quien := persona(t, pool, correo, 40, "Rep"+string(rune('M'+i)))
		if _, err := s.Reportar(ctx, quien, primera.ID, "contenido-peligroso", "detalles"); err != nil {
			t.Fatalf("Reportar: %v", err)
		}
	}
	if err := s.Retirar(ctx, moderador, primera.ID, "instrucciones de consumo"); err != nil {
		t.Fatalf("Retirar: %v", err)
	}

	var accion, motivo, quien string
	if err := pool.QueryRow(ctx, `
		SELECT accion::text, motivo, moderator_id::text
		FROM moderation_actions WHERE story_id = $1`, primera.ID).Scan(&accion, &motivo, &quien); err != nil {
		t.Fatalf("SELECT auditoría: %v", err)
	}
	if accion != "RETIRADA" || motivo != "instrucciones de consumo" || quien != moderador {
		t.Errorf("auditoría = %s/%q/%s, no coincide", accion, motivo, quien)
	}

	// La segunda historia del mismo autor tiene que llegar a la cola con el
	// antecedente a la vista.
	segunda := historia(t, s, autor, "Segunda pasada de la raya", true)
	for i, correo := range []string{"s@undiamas.mx", "t@undiamas.mx", "u@undiamas.mx"} {
		quien := persona(t, pool, correo, 40, "Rep"+string(rune('S'+i)))
		if _, err := s.Reportar(ctx, quien, segunda.ID, "acoso", ""); err != nil {
			t.Fatalf("Reportar: %v", err)
		}
	}
	cola, err := s.Cola(ctx, 50)
	if err != nil {
		t.Fatalf("Cola: %v", err)
	}
	if len(cola) != 1 {
		t.Fatalf("la cola tiene %d, se esperaba 1", len(cola))
	}
	if cola[0].RetiradasPrevias != 1 {
		t.Errorf("retiradasPrevias = %d, se esperaba 1", cola[0].RetiradasPrevias)
	}
}
