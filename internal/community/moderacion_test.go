package community

import "testing"

func TestRechazaPlanesDeConsumo(t *testing.T) {
	casos := map[string]Motivo{
		"yo lo conseguía en el puente de la colonia":      MotivoSuministro,
		"si quieren les paso el contacto de mi dealer":    MotivoSuministro,
		"donde comprar sin que te vean":                   MotivoSuministro,
		"me lo vendía un compañero del trabajo":           MotivoSuministro,
		"escríbeme al privado y te digo":                  MotivoSuministro,
		"empecé con 2 gramos diarios y acabé en el doble": MotivoDosis,
		"me tomaba media pastilla para dormir":            MotivoDosis,
		"llegué a 500mg al día":                           MotivoDosis,
		"me gastaba $800 cada fin de semana":              MotivoPrecio,
		"eran como 300 pesos la noche":                    MotivoPrecio,
	}
	for texto, want := range casos {
		got, msg := Revisar(texto)
		if got != want {
			t.Errorf("Revisar(%q) = %q, se esperaba %q", texto, got, want)
		}
		if got != "" && msg == "" {
			t.Errorf("Revisar(%q) rechazó sin explicar por qué", texto)
		}
	}
}

// Lo que NO puede rechazar. Estas frases son el habla normal de la recuperación
// y bloquearlas sería peor que el problema que resuelve el filtro: alguien
// contando su proceso y la app diciéndole que su historia no se puede publicar.
func TestNoRechazaElHablaNormalDeLaRecuperacion(t *testing.T) {
	sanas := []string{
		"conseguí ayuda cuando por fin se lo dije a mi hermana",
		"lo que me sirvió fue conseguir un grupo cerca de mi casa",
		"llevo tres meses y todavía me cuesta los viernes",
		"mi terapeuta me dijo que fuera a un grupo y ahí sigo",
		"vendí la moto para pagar el tratamiento",
		"pasé de no poder dormir a dormir de corrido",
		"el primer mes fue horrible, el segundo ya no tanto",
		"tengo dos hijas y por ellas empecé",
		"me ayudó mucho salir a correr en las mañanas",
		"a los 90 días me sentí otra persona",
	}
	for _, texto := range sanas {
		if motivo, _ := Revisar(texto); motivo != "" {
			t.Errorf("Revisar(%q) rechazó por %q; es habla normal de recuperación", texto, motivo)
		}
	}
}

func TestRevisarNormalizaAcentosYEspacios(t *testing.T) {
	if motivo, _ := Revisar("dónde   conseguir  sin  bronca"); motivo != MotivoSuministro {
		t.Errorf("con acentos y espacios de más se escapó: %q", motivo)
	}
}

// Los datos personales avisan, no bloquean: la decisión es de quien escribe.
func TestDatosPersonalesAvisanPeroNoBloquean(t *testing.T) {
	texto := "si alguien quiere hablar, mi correo es ana.lopez@correo.mx"
	if motivo, _ := Revisar(texto); motivo != "" {
		t.Errorf("un correo NO debe impedir publicar, se rechazó por %q", motivo)
	}
	avisos := Avisos(texto)
	if len(avisos) == 0 || avisos[0].Tipo != "correo" {
		t.Errorf("avisos = %v, se esperaba uno de tipo correo", avisos)
	}
}

func TestAvisosDetectaTelefonoYEnlace(t *testing.T) {
	casos := map[string]string{
		"escríbanme al 55 1234 5678 si necesitan algo": "telefono",
		"subí mi historia completa en miblog.com":      "enlace",
		"búsquenme en @ana.recupera":                   "enlace",
	}
	for texto, tipo := range casos {
		avisos := Avisos(texto)
		encontrado := false
		for _, a := range avisos {
			if a.Tipo == tipo {
				encontrado = true
			}
		}
		if !encontrado {
			t.Errorf("Avisos(%q) = %v, faltaba %q", texto, avisos, tipo)
		}
	}
}

func TestUnaHistoriaLimpiaNoAvisaNada(t *testing.T) {
	limpia := "Llevo ocho meses. Lo más difícil fue el primer mes, sobre todo " +
		"las noches. Lo que me funcionó fue tener a quién escribirle a las tres de la mañana."
	if motivo, _ := Revisar(limpia); motivo != "" {
		t.Errorf("rechazó una historia limpia: %q", motivo)
	}
	if avisos := Avisos(limpia); len(avisos) != 0 {
		t.Errorf("avisó de más: %v", avisos)
	}
}
