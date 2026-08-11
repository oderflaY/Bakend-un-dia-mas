package community

import (
	"regexp"
	"strings"
)

// Este archivo hace dos cosas que se parecen y no lo son:
//
//   - `Revisar` **rechaza**: dosis, precios y dónde conseguir. Quien entra al
//     muro está en un momento vulnerable, y "yo lo conseguía en X" no es una
//     experiencia, es un plan de consumo. Eso no se publica.
//   - `Avisos` **advierte**: correos, teléfonos y enlaces. No bloquea. Alguien
//     puede querer dejar su contacto a propósito y esa decisión es suya; lo que
//     no puede es publicarlo sin darse cuenta de que el muro es público.
//
// El filtro es deliberadamente simple —expresiones regulares sobre texto
// normalizado— y por lo tanto imperfecto. La decisión de fondo: es preferible
// dejar pasar algo dudoso que censurar a alguien contando su recuperación. Por
// eso los patrones son específicos ("donde conseguir") y no genéricos
// ("conseguir"): en este dominio "conseguí ayuda" y "conseguir un grupo" son
// frases sanas y frecuentes, y rechazarlas sería peor que el problema.

// Motivo de rechazo. Es un código estable: la app lo usa para elegir el texto
// que enseña.
type Motivo string

const (
	MotivoDosis      Motivo = "dosis"
	MotivoPrecio     Motivo = "precio"
	MotivoSuministro Motivo = "suministro"
)

// Aviso es una advertencia no bloqueante.
type Aviso struct {
	Tipo    string `json:"tipo"`    // "correo" | "telefono" | "enlace"
	Mensaje string `json:"mensaje"` // listo para enseñar tal cual
}

var (
	// Cantidades con unidad: "2 gramos", "500mg", "tres líneas", "media pastilla".
	reDosis = regexp.MustCompile(`(?i)\b(\d+([.,]\d+)?|un|una|dos|tres|cuatro|cinco|seis|media|medio)\s*` +
		`(mg|ml|gr|grs|g|gramos?|miligramos?|mililitros?|onzas?|lineas?|rayas?|pastillas?|` +
		`tachas?|dosis|papeles?|toques?|jalones?|caballos?)\b`)

	// Precios: "$300", "300 pesos", "cuesta 50 varos".
	rePrecio = regexp.MustCompile(`(?i)(\$\s*\d+|\b\d+\s*(pesos|varos|lucas|dolares|usd|mxn|mx)\b)`)

	// Dónde y a quién. Frases, no palabras sueltas.
	reSuministro = regexp.MustCompile(`(?i)(` +
		`donde (comprar|conseguir|venden|consigo|conseguia|consigues|pillar)|` +
		`d[oó]nde lo (compro|consigo|conseguia|conseguía)|` +
		`lo (compro|compraba|consigo|conseguia|conseguía|pillaba) en\b|` +
		`me lo (vende|vendia|vendía|pasa|pasaba|surte|surtia|surtía)\b|` +
		`te (paso|doy|comparto) (el|mi) (contacto|dato|numero|número|whats)|` +
		`mi (dealer|conecte|conecta|proveedor)|` +
		`\bdealer\b|\bnarcomenudeo\b|\btienda de (la|el)\b|` +
		`(vendo|surto|consigo|manejo) (de |la |el )?(buena|pura|barato|barata)|` +
		`pregunta por\b|` +
		`escr[ií]beme (al|por)\b` +
		`)`)

	reCorreo   = regexp.MustCompile(`(?i)\b[\w.+-]+@[\w-]+\.[a-z]{2,}\b`)
	reEnlace   = regexp.MustCompile(`(?i)(https?://|\bwww\.|\b[\w-]+\.(com|mx|net|org|io|me|ly)\b|\bt\.me/|@[\w.]{3,})`)
	reTelefono = regexp.MustCompile(`(?:\+?\d[\d\s().-]{7,}\d)`)
)

// Revisar decide si el texto se puede publicar. Devuelve el motivo y un mensaje
// para la persona, o ("", "") si está bien.
//
// El mensaje explica el porqué en vez de decir "contenido no permitido": quien
// escribe una historia sincera y se topa con un rechazo seco asume que la app lo
// está juzgando a él.
func Revisar(textos ...string) (Motivo, string) {
	junto := normalizar(strings.Join(textos, "\n"))

	switch {
	case reSuministro.MatchString(junto):
		return MotivoSuministro, "No podemos publicar dónde o a quién se consigue. " +
			"Quien lee esto puede estar a punto de recaer; cuenta cómo te fue, no dónde ir."
	case reDosis.MatchString(junto):
		return MotivoDosis, "Quita las cantidades y las dosis. " +
			"Tu experiencia sirve igual sin ellas, y para quien está mal son una referencia peligrosa."
	case rePrecio.MatchString(junto):
		return MotivoPrecio, "Quita los precios. " +
			"Lo que ayuda de tu historia es lo que viviste, no cuánto costaba."
	}
	return "", ""
}

// Avisos detecta datos personales. Nunca bloquea: se devuelven junto con la
// historia ya publicada para que la app los enseñe.
func Avisos(textos ...string) []Aviso {
	junto := strings.Join(textos, "\n")
	out := []Aviso{}

	if reCorreo.MatchString(junto) {
		out = append(out, Aviso{"correo", "Dejaste un correo visible. El muro es público: cualquiera puede leerlo."})
	}
	// El correo ya contiene dígitos y arrobas que disparan las otras dos, así
	// que se comprueban sobre el texto sin correos.
	sinCorreos := reCorreo.ReplaceAllString(junto, " ")
	if reTelefono.MatchString(sinCorreos) {
		out = append(out, Aviso{"telefono", "Parece que dejaste un teléfono. El muro es público: cualquiera puede leerlo."})
	}
	if reEnlace.MatchString(sinCorreos) {
		out = append(out, Aviso{"enlace", "Dejaste un enlace o un usuario de red social. El muro es público."})
	}
	return out
}

// normalizar quita acentos y colapsa espacios para que "dónde  conseguir" y
// "donde conseguir" sean lo mismo. No quita puntuación: los patrones la usan.
func normalizar(s string) string {
	var b strings.Builder
	b.Grow(len(s))
	for _, r := range strings.ToLower(s) {
		if d, ok := sinAcento[r]; ok {
			b.WriteRune(d)
			continue
		}
		b.WriteRune(r)
	}
	return strings.Join(strings.Fields(b.String()), " ")
}

var sinAcento = map[rune]rune{
	'á': 'a', 'é': 'e', 'í': 'i', 'ó': 'o', 'ú': 'u', 'ü': 'u',
}
