// Package httpx concentra el contrato HTTP: cómo se serializa una respuesta,
// cómo se ve un error y cómo se leen los parámetros de paginación.
package httpx

import (
	"encoding/json"
	"errors"
	"io"
	"log/slog"
	"net/http"
	"strconv"

	"github.com/jackc/pgx/v5"
)

type ErrorBody struct {
	Error   string `json:"error"`   // código estable, para que la app haga switch
	Message string `json:"message"` // texto para humanos, nunca para la UI final
}

func JSON(w http.ResponseWriter, status int, v any) {
	w.Header().Set("Content-Type", "application/json; charset=utf-8")
	w.WriteHeader(status)
	if v == nil {
		return
	}
	if err := json.NewEncoder(w).Encode(v); err != nil {
		slog.Error("no se pudo escribir la respuesta", "err", err)
	}
}

func Error(w http.ResponseWriter, status int, code, msg string) {
	JSON(w, status, ErrorBody{Error: code, Message: msg})
}

// Decode lee el cuerpo con un tope de tamaño y rechaza campos desconocidos:
// un typo en la app se convierte en 400 explícito, no en un default silencioso.
func Decode(w http.ResponseWriter, r *http.Request, dst any) error {
	r.Body = http.MaxBytesReader(w, r.Body, 1<<20)
	dec := json.NewDecoder(r.Body)
	dec.DisallowUnknownFields()
	if err := dec.Decode(dst); err != nil {
		return err
	}
	if err := dec.Decode(&struct{}{}); !errors.Is(err, io.EOF) {
		return errors.New("el cuerpo debe contener un solo objeto JSON")
	}
	return nil
}

// IsNotFound decide si un error de la base es un 404. Además de "no hay filas",
// cuenta el id mal formado: un {id} que no es UUID hace que Postgres responda
// 22P02, y eso es un cliente pidiendo algo que no existe, no un fallo nuestro.
func IsNotFound(err error) bool {
	if errors.Is(err, pgx.ErrNoRows) {
		return true
	}
	var pgErr interface{ SQLState() string }
	return errors.As(err, &pgErr) && pgErr.SQLState() == "22P02"
}

// Limit acota el parámetro ?limit= al rango [1, max]. Sin esto volveríamos al
// hallazgo #14: traerse la colección entera y recortar en memoria.
func Limit(r *http.Request, def, max int) int {
	v, err := strconv.Atoi(r.URL.Query().Get("limit"))
	if err != nil || v < 1 {
		return def
	}
	return min(v, max)
}
