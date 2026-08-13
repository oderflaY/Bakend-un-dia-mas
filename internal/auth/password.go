package auth

// Recuperar la contraseña con un código de seis dígitos al correo.
//
// Todo el diseño gira alrededor de dos cosas que pelean entre sí: el código
// tiene que ser cómodo de teclear en un teléfono (seis dígitos) y no tiene que
// ser adivinable. Seis dígitos son un millón de combinaciones, que un script
// recorre en minutos si se le deja. Lo que lo hace seguro no es el código, es
// el cerco: caduca en 15 minutos, muere a los 5 fallos, se usa una sola vez, y
// la ruta está limitada por IP como el resto de /v1/auth.
//
// La otra regla es no filtrar quién tiene cuenta. `forgot` responde igual para
// un correo registrado que para uno que no existe: si contestara distinto,
// sería un buscador de usuarios de esta app, y aquí tener cuenta significa
// estar en recuperación de una adicción. Eso no se confirma a nadie.

import (
	"context"
	"crypto/rand"
	"errors"
	"fmt"
	"log/slog"
	"net/http"
	"net/mail"
	"strings"
	"time"

	"github.com/jackc/pgx/v5"
	"golang.org/x/crypto/bcrypt"

	"github.com/oderflaY/Bakend-un-dia-mas/internal/httpx"
	"github.com/oderflaY/Bakend-un-dia-mas/internal/mailer"
)

const (
	resetTTL           = 15 * time.Minute
	resetMaxIntentos   = 5
	resetReenvioMinimo = time.Minute
)

var ErrResetInvalido = errors.New("código inválido o caducado")

// Correo es lo que auth necesita de un servidor de correo. Es una interfaz y no
// el tipo concreto para poder probar el flujo sin mandar nada.
type Correo interface {
	Configurado() bool
	Enviar(para, asunto, cuerpo string) error
}

// ---------------------------------------------------------------- rutas

func (h *Handler) forgot(w http.ResponseWriter, r *http.Request) {
	var in struct {
		Email string `json:"email"`
	}
	if err := httpx.Decode(w, r, &in); err != nil {
		httpx.Error(w, http.StatusBadRequest, "invalid-argument", err.Error())
		return
	}
	if _, err := mail.ParseAddress(in.Email); err != nil {
		httpx.Error(w, http.StatusBadRequest, "invalid-argument", "correo inválido")
		return
	}

	// A partir de aquí la respuesta es siempre la misma, pase lo que pase. Los
	// fallos se registran para quien opera el servidor, no para quien llama.
	if err := h.mandarCodigo(r.Context(), in.Email); err != nil &&
		!errors.Is(err, pgx.ErrNoRows) && !errors.Is(err, mailer.ErrSinConfigurar) {
		slog.Error("no se pudo mandar el código de recuperación", "err", err)
	}
	w.WriteHeader(http.StatusNoContent)
}

func (h *Handler) mandarCodigo(ctx context.Context, email string) error {
	u, err := h.store.UserByEmail(ctx, email)
	if err != nil {
		return err // incluido "no existe": el llamador ya lo trata como silencio
	}
	if !h.correo.Configurado() {
		return mailer.ErrSinConfigurar
	}

	// Sin esto, cualquiera podría llenar el buzón de otra persona pidiendo
	// códigos en bucle. El límite por IP no lo cubre: bastarían varias IPs.
	reciente, err := h.store.ResetReciente(ctx, u.ID, resetReenvioMinimo)
	if err != nil {
		return err
	}
	if reciente {
		return nil
	}

	codigo, err := codigoSeisDigitos()
	if err != nil {
		return err
	}
	hash, err := bcrypt.GenerateFromPassword([]byte(codigo), bcrypt.DefaultCost)
	if err != nil {
		return err
	}
	if err := h.store.CrearReset(ctx, u.ID, string(hash), time.Now().Add(resetTTL)); err != nil {
		return err
	}

	return h.correo.Enviar(u.Email, "Tu código para recuperar la contraseña",
		fmt.Sprintf(`Hola:

Tu código para recuperar la contraseña de Un Día Más es:

    %s

Caduca en 15 minutos y solo sirve una vez.

Si no pediste este código, no hace falta que hagas nada: sin él nadie puede
entrar a tu cuenta. Nadie de Un Día Más te lo va a pedir por mensaje ni por
teléfono.
`, codigo))
}

func (h *Handler) reset(w http.ResponseWriter, r *http.Request) {
	var in struct {
		Email    string `json:"email"`
		Codigo   string `json:"codigo"`
		Password string `json:"password"`
	}
	if err := httpx.Decode(w, r, &in); err != nil {
		httpx.Error(w, http.StatusBadRequest, "invalid-argument", err.Error())
		return
	}
	// El mismo mínimo que en el registro: cambiar la contraseña no puede ser la
	// puerta de atrás para ponerse una de tres letras.
	if len(in.Password) < 8 {
		httpx.Error(w, http.StatusBadRequest, "invalid-argument", "la contraseña necesita 8 caracteres o más")
		return
	}
	in.Codigo = strings.TrimSpace(in.Codigo)

	hash, err := bcrypt.GenerateFromPassword([]byte(in.Password), bcrypt.DefaultCost)
	if err != nil {
		httpx.Error(w, http.StatusInternalServerError, "internal", "no se pudo procesar la contraseña")
		return
	}

	err = h.store.UsarReset(r.Context(), in.Email, in.Codigo, string(hash))
	if errors.Is(err, ErrResetInvalido) || errors.Is(err, pgx.ErrNoRows) {
		// Un solo error para código malo, caducado, gastado y correo inexistente:
		// distinguirlos volvería a filtrar qué cuentas existen.
		httpx.Error(w, http.StatusBadRequest, "invalid-code", "código inválido o caducado")
		return
	}
	if err != nil {
		httpx.Error(w, http.StatusInternalServerError, "internal", "no se pudo cambiar la contraseña")
		return
	}
	// Sin cuerpo: la app manda a la pantalla de entrar. No se emiten tokens
	// aquí a propósito —quien cambia la contraseña vuelve a identificarse—, y
	// las sesiones viejas ya quedaron revocadas dentro de UsarReset.
	w.WriteHeader(http.StatusNoContent)
}

// codigoSeisDigitos usa crypto/rand: math/rand haría los códigos predecibles a
// partir de un par de muestras, que es justo lo que no puede pasar aquí.
func codigoSeisDigitos() (string, error) {
	var b [3]byte
	if _, err := rand.Read(b[:]); err != nil {
		return "", err
	}
	n := (int(b[0])<<16 | int(b[1])<<8 | int(b[2])) % 1000000
	return fmt.Sprintf("%06d", n), nil
}
