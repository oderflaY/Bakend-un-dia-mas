// Package addiction es el catálogo de tipos de adicción.
//
// Existe como paquete propio por la misma razón que internal/risk: lo necesitan
// dos paquetes que no pueden importarse entre sí (auth lo valida al registrar,
// users al editar el perfil), y un catálogo duplicado en dos archivos es la
// forma más rápida de que "cannabis" y "marihuana" acaben siendo dos cosas
// distintas en la misma base.
//
// Los códigos son estables: la app hace switch sobre ellos para elegir iconos y
// textos. Cambiar uno es una migración de datos, no un renombrado.
//
// El catálogo incluye `juego` a propósito: la adicción no es solo a sustancias,
// y dejarlo fuera obligaría a esas personas a registrarse como "otra".
package addiction

import "strings"

type Type string

const (
	Alcohol         Type = "alcohol"
	Tabaco          Type = "tabaco"
	Cannabis        Type = "cannabis"
	Cocaina         Type = "cocaina"
	Metanfetamina   Type = "metanfetamina"
	Opioides        Type = "opioides"
	Benzodiacepinas Type = "benzodiacepinas"
	Inhalantes      Type = "inhalantes"
	Juego           Type = "juego"
	Otra            Type = "otra"
)

// catalogo es el orden en que la app puede pintar la lista: de lo más frecuente
// a lo menos, con "otra" siempre al final.
var catalogo = []Type{
	Alcohol, Tabaco, Cannabis, Cocaina, Metanfetamina,
	Opioides, Benzodiacepinas, Inhalantes, Juego, Otra,
}

// sinonimos traduce lo que la gente escribe al código del catálogo. No es
// exhaustivo ni pretende serlo: cubre lo que manda una app que todavía no
// migró sus etiquetas.
var sinonimos = map[string]Type{
	"alcoholismo": Alcohol, "cerveza": Alcohol, "bebida": Alcohol,
	"nicotina": Tabaco, "cigarro": Tabaco, "cigarrillo": Tabaco, "vape": Tabaco, "vapeo": Tabaco,
	"marihuana": Cannabis, "mota": Cannabis, "thc": Cannabis,
	"cocaína": Cocaina, "coca": Cocaina, "crack": Cocaina,
	"cristal": Metanfetamina, "meta": Metanfetamina, "anfetaminas": Metanfetamina,
	"heroina": Opioides, "heroína": Opioides, "fentanilo": Opioides, "opiaceos": Opioides,
	"benzos": Benzodiacepinas, "clonazepam": Benzodiacepinas,
	"solventes": Inhalantes, "pegamento": Inhalantes,
	"apuestas": Juego, "ludopatia": Juego, "ludopatía": Juego, "casino": Juego,
}

// Catalogo devuelve una copia: el catálogo no se muta desde fuera.
func Catalogo() []Type {
	return append([]Type{}, catalogo...)
}

// Parse acepta el código exacto o un sinónimo conocido. A diferencia de
// risk.Parse, aquí un valor desconocido **no** cae a un valor por defecto: se
// rechaza. Inventar "alcohol" porque alguien escribió cualquier cosa mandaría a
// esa persona material de apoyo que no le corresponde.
func Parse(s string) (Type, bool) {
	s = strings.ToLower(strings.TrimSpace(s))
	if s == "" {
		return "", false
	}
	for _, t := range catalogo {
		if string(t) == s {
			return t, true
		}
	}
	if t, ok := sinonimos[s]; ok {
		return t, true
	}
	return "", false
}

// ParseLista normaliza una lista completa y quita duplicados conservando el
// orden. Devuelve el primer valor inválido que encuentre, para poder decirle a
// la app cuál fue y no un "datos inválidos" a secas.
func ParseLista(in []string) ([]Type, string) {
	out := make([]Type, 0, len(in))
	vistos := map[Type]bool{}
	for _, raw := range in {
		t, ok := Parse(raw)
		if !ok {
			return nil, raw
		}
		if vistos[t] {
			continue
		}
		vistos[t] = true
		out = append(out, t)
	}
	return out, ""
}

// Contiene dice si la principal está dentro de la lista. La regla la aplican
// auth y users: la adicción principal tiene que ser una de las declaradas, o el
// perfil quedaría diciendo dos cosas distintas.
func Contiene(lista []Type, t Type) bool {
	for _, v := range lista {
		if v == t {
			return true
		}
	}
	return false
}

// Strings convierte para el driver de Postgres, que no conoce el tipo Type.
func Strings(ts []Type) []string {
	out := make([]string, len(ts))
	for i, t := range ts {
		out[i] = string(t)
	}
	return out
}

// Types es la conversión inversa, al leer de la base.
func Types(ss []string) []Type {
	out := make([]Type, len(ss))
	for i, s := range ss {
		out[i] = Type(s)
	}
	return out
}
