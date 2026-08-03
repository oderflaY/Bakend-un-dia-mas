// Package risk es la única fuente de verdad del semáforo.
//
// El análisis del backend anterior detectó tres vocabularios para el mismo valor
// (GREEN/YELLOW/RED en Firestore, VERDE/AMARILLO/ROJO en dominio y en las
// Cloud Functions) con la traducción duplicada en dos archivos. Aquí hay una
// sola representación interna y una sola tabla de conversión.
package risk

import "strings"

type Level int

const (
	Green Level = iota
	Yellow
	Red
)

// Code es lo que se guarda en Postgres (enum risk_level).
func (l Level) Code() string {
	switch l {
	case Yellow:
		return "yellow"
	case Red:
		return "red"
	default:
		return "green"
	}
}

// String es lo que ve la app KMP, que habla en español.
func (l Level) String() string {
	switch l {
	case Yellow:
		return "AMARILLO"
	case Red:
		return "ROJO"
	default:
		return "VERDE"
	}
}

func (l Level) MarshalJSON() ([]byte, error) {
	return []byte(`"` + l.String() + `"`), nil
}

// Parse acepta ambos idiomas y cae a Green ante cualquier valor desconocido.
// Es deliberado: inventar un ROJO por un dato corrupto dispararía el protocolo
// de emergencia de alguien sin motivo.
func Parse(s string) Level {
	switch strings.ToUpper(strings.TrimSpace(s)) {
	case "YELLOW", "AMARILLO":
		return Yellow
	case "RED", "ROJO":
		return Red
	default:
		return Green
	}
}
