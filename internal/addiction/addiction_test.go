package addiction

import "testing"

func TestParseAceptaCodigoYSinonimo(t *testing.T) {
	casos := map[string]Type{
		"alcohol":     Alcohol,
		"ALCOHOL":     Alcohol,
		"  alcohol  ": Alcohol,
		"cerveza":     Alcohol,
		"marihuana":   Cannabis,
		"nicotina":    Tabaco,
		"vape":        Tabaco,
		"cocaína":     Cocaina,
		"cristal":     Metanfetamina,
		"apuestas":    Juego,
		"ludopatía":   Juego,
	}
	for in, want := range casos {
		got, ok := Parse(in)
		if !ok || got != want {
			t.Errorf("Parse(%q) = %q, %v; se esperaba %q", in, got, ok, want)
		}
	}
}

// A diferencia de risk.Parse, un valor desconocido se rechaza en vez de caer a
// un valor por defecto: mandarle a alguien el material de otra adicción sería
// peor que pedirle que lo corrija.
func TestParseRechazaLoDesconocido(t *testing.T) {
	for _, in := range []string{"", "   ", "chela", "azucar", "trabajo", "alcohol2"} {
		if got, ok := Parse(in); ok {
			t.Errorf("Parse(%q) devolvió %q; debía rechazarse", in, got)
		}
	}
}

func TestParseListaQuitaDuplicadosYConservaOrden(t *testing.T) {
	got, malo := ParseLista([]string{"tabaco", "cerveza", "alcohol", "nicotina"})
	if malo != "" {
		t.Fatalf("valor inválido inesperado: %q", malo)
	}
	if len(got) != 2 || got[0] != Tabaco || got[1] != Alcohol {
		t.Errorf("lista = %v, se esperaba [tabaco alcohol]", got)
	}
}

// El valor inválido se devuelve para poder decirle a la app cuál fue.
func TestParseListaSenalaElValorMalo(t *testing.T) {
	got, malo := ParseLista([]string{"alcohol", "chela", "tabaco"})
	if malo != "chela" {
		t.Errorf("malo = %q, se esperaba \"chela\"", malo)
	}
	if got != nil {
		t.Errorf("con un valor inválido no debe devolverse lista parcial: %v", got)
	}
}

func TestCatalogoNoSeMutaDesdeFuera(t *testing.T) {
	c := Catalogo()
	c[0] = "manipulado"
	if Catalogo()[0] != Alcohol {
		t.Error("el catálogo se puede mutar desde fuera")
	}
}

func TestIdaYVueltaConPostgres(t *testing.T) {
	original := []Type{Alcohol, Juego}
	if got := Types(Strings(original)); len(got) != 2 || got[0] != Alcohol || got[1] != Juego {
		t.Errorf("ida y vuelta = %v", got)
	}
}
