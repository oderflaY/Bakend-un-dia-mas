package auth

import (
	"context"
	"net/http"
	"strings"

	"github.com/oderflaY/Bakend-un-dia-mas/internal/httpx"
)

type ctxKey struct{}

// Identity es el sujeto autenticado de la petición. Nunca se construye a partir
// del cuerpo: solo del token verificado. Es el equivalente a usar request.auth.uid
// en lugar de un uid del payload, que era lo único que agentChat hacía bien.
type Identity struct {
	UserID string
	Role   Role
}

// Middleware exige un Bearer token válido.
func (t *TokenIssuer) Middleware(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		raw, ok := strings.CutPrefix(r.Header.Get("Authorization"), "Bearer ")
		if !ok || raw == "" {
			httpx.Error(w, http.StatusUnauthorized, "unauthenticated", "falta el header Authorization")
			return
		}
		claims, err := t.Parse(raw)
		if err != nil {
			httpx.Error(w, http.StatusUnauthorized, "unauthenticated", "token inválido o expirado")
			return
		}
		id := Identity{UserID: claims.Subject, Role: claims.Role}
		next.ServeHTTP(w, r.WithContext(context.WithValue(r.Context(), ctxKey{}, id)))
	})
}

// RequireRole se encadena después de Middleware.
func RequireRole(role Role, next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if id, ok := From(r.Context()); !ok || id.Role != role {
			httpx.Error(w, http.StatusForbidden, "forbidden", "tu rol no permite esta operación")
			return
		}
		next.ServeHTTP(w, r)
	})
}

func From(ctx context.Context) (Identity, bool) {
	id, ok := ctx.Value(ctxKey{}).(Identity)
	return id, ok
}

// MustFrom se usa dentro de rutas ya protegidas por Middleware.
func MustFrom(ctx context.Context) Identity {
	id, _ := From(ctx)
	return id
}
