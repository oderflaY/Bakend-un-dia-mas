package auth

import (
	"errors"
	"net/http"
	"net/mail"
	"strings"
	"time"

	"golang.org/x/crypto/bcrypt"

	"github.com/oderflaY/Bakend-un-dia-mas/internal/addiction"
	"github.com/oderflaY/Bakend-un-dia-mas/internal/httpx"
)

type Handler struct {
	store  *Store
	issuer *TokenIssuer
}

func NewHandler(store *Store, issuer *TokenIssuer) *Handler {
	return &Handler{store: store, issuer: issuer}
}

func (h *Handler) Routes(mux *http.ServeMux) {
	mux.HandleFunc("POST /v1/auth/register", h.register)
	mux.HandleFunc("POST /v1/auth/login", h.login)
	mux.HandleFunc("POST /v1/auth/refresh", h.refresh)
	mux.Handle("POST /v1/auth/logout", h.issuer.Middleware(http.HandlerFunc(h.logout)))
}

type credentials struct {
	Email       string `json:"email"`
	Password    string `json:"password"`
	DisplayName string `json:"displayName,omitempty"`

	// Perfil de recuperación, todo opcional: se puede registrar primero y
	// contestar el onboarding después. Lo que no se puede es mandar un tipo de
	// adicción que no existe.
	Adicciones    []string `json:"adicciones,omitempty"`
	Principal     string   `json:"adiccionPrincipal,omitempty"`
	ConsumoDesde  *string  `json:"consumoDesde,omitempty"` // YYYY-MM-DD
	EnTratamiento bool     `json:"enTratamiento,omitempty"`
}

func (h *Handler) register(w http.ResponseWriter, r *http.Request) {
	var in credentials
	if err := httpx.Decode(w, r, &in); err != nil {
		httpx.Error(w, http.StatusBadRequest, "invalid-argument", err.Error())
		return
	}
	if _, err := mail.ParseAddress(in.Email); err != nil {
		httpx.Error(w, http.StatusBadRequest, "invalid-argument", "correo inválido")
		return
	}
	if len(in.Password) < 8 {
		httpx.Error(w, http.StatusBadRequest, "invalid-argument", "la contraseña necesita 8 caracteres o más")
		return
	}

	perfil, msg := parsePerfil(in)
	if msg != "" {
		httpx.Error(w, http.StatusBadRequest, "invalid-argument", msg)
		return
	}

	hash, err := bcrypt.GenerateFromPassword([]byte(in.Password), bcrypt.DefaultCost)
	if err != nil {
		httpx.Error(w, http.StatusInternalServerError, "internal", "no se pudo procesar la contraseña")
		return
	}
	perfil.Email = strings.TrimSpace(in.Email)
	perfil.Hash = string(hash)
	perfil.DisplayName = in.DisplayName

	// El rol no se acepta del cuerpo en ningún caso: es el equivalente de
	// noTocaRol() en firestore.rules. CreateUser siempre inserta 'patient'.
	u, err := h.store.CreateUser(r.Context(), perfil)
	if errors.Is(err, ErrEmailTaken) {
		httpx.Error(w, http.StatusConflict, "email-taken", err.Error())
		return
	}
	if err != nil {
		httpx.Error(w, http.StatusInternalServerError, "internal", "no se pudo crear la cuenta")
		return
	}
	h.issue(w, r, u, http.StatusCreated)
}

// parsePerfil valida el perfil de recuperación y devuelve el mensaje de error
// listo para el usuario, o "" si todo está bien.
//
// Se valida aquí y no en la base porque el mensaje importa: "el tipo de adicción
// 'chela' no existe" le dice a quien integra la app qué corregir, mientras que
// un CHECK violado en Postgres solo dice que algo falló.
func parsePerfil(in credentials) (NewUser, string) {
	var out NewUser

	lista, malo := addiction.ParseLista(in.Adicciones)
	if malo != "" {
		return out, "tipo de adicción desconocido: " + malo
	}
	out.Adicciones = lista

	if in.Principal != "" {
		p, ok := addiction.Parse(in.Principal)
		if !ok {
			return out, "tipo de adicción desconocido: " + in.Principal
		}
		// Si alguien manda solo la principal, esa es su lista. Exigir que la
		// repitiera en los dos campos sería burocracia sin ganancia.
		if len(out.Adicciones) == 0 {
			out.Adicciones = []addiction.Type{p}
		}
		if !addiction.Contiene(out.Adicciones, p) {
			return out, "la adicción principal tiene que estar en la lista de adicciones"
		}
		out.Principal = p
	} else if len(out.Adicciones) == 1 {
		// Con una sola declarada no hay ambigüedad que resolver.
		out.Principal = out.Adicciones[0]
	}

	if in.ConsumoDesde != nil && *in.ConsumoDesde != "" {
		fecha, err := time.Parse(time.DateOnly, *in.ConsumoDesde)
		if err != nil {
			return out, "consumoDesde debe tener el formato YYYY-MM-DD"
		}
		if fecha.After(time.Now()) {
			return out, "consumoDesde no puede estar en el futuro"
		}
		out.ConsumoDesde = &fecha
	}

	out.EnTratamiento = in.EnTratamiento
	return out, ""
}

// login usa el mismo struct, así que un cliente puede mandar de más sin que
// httpx.Decode lo rechace por campo desconocido; aquí simplemente se ignora.
func (h *Handler) login(w http.ResponseWriter, r *http.Request) {
	var in credentials
	if err := httpx.Decode(w, r, &in); err != nil {
		httpx.Error(w, http.StatusBadRequest, "invalid-argument", err.Error())
		return
	}

	u, err := h.store.UserByEmail(r.Context(), in.Email)
	if err == nil {
		err = bcrypt.CompareHashAndPassword([]byte(u.PasswordHash), []byte(in.Password))
	}
	if err != nil {
		// Mismo error para "no existe" y "contraseña mala": no se filtra
		// qué correos están registrados.
		httpx.Error(w, http.StatusUnauthorized, "invalid-credentials", "correo o contraseña incorrectos")
		return
	}

	_ = h.store.TouchLogin(r.Context(), u.ID)
	h.issue(w, r, u, http.StatusOK)
}

func (h *Handler) refresh(w http.ResponseWriter, r *http.Request) {
	var in struct {
		RefreshToken string `json:"refreshToken"`
	}
	if err := httpx.Decode(w, r, &in); err != nil || in.RefreshToken == "" {
		httpx.Error(w, http.StatusBadRequest, "invalid-argument", "falta refreshToken")
		return
	}
	u, err := h.store.ConsumeRefreshToken(r.Context(), HashToken(in.RefreshToken))
	if err != nil {
		httpx.Error(w, http.StatusUnauthorized, "invalid-credentials", "refresh token inválido o expirado")
		return
	}
	h.issue(w, r, u, http.StatusOK)
}

func (h *Handler) logout(w http.ResponseWriter, r *http.Request) {
	if err := h.store.RevokeAllTokens(r.Context(), MustFrom(r.Context()).UserID); err != nil {
		httpx.Error(w, http.StatusInternalServerError, "internal", "no se pudo cerrar la sesión")
		return
	}
	w.WriteHeader(http.StatusNoContent)
}

// issue rota siempre el par de tokens: cada refresh invalida el anterior.
func (h *Handler) issue(w http.ResponseWriter, r *http.Request, u User, status int) {
	access, err := h.issuer.NewAccessToken(u.ID, u.Role)
	if err != nil {
		httpx.Error(w, http.StatusInternalServerError, "internal", "no se pudo emitir el token")
		return
	}
	raw, hash, err := NewRefreshToken()
	if err != nil {
		httpx.Error(w, http.StatusInternalServerError, "internal", "no se pudo emitir el token")
		return
	}
	expires := time.Now().Add(h.issuer.RefreshTTL())
	if err := h.store.SaveRefreshToken(r.Context(), hash, u.ID, expires); err != nil {
		httpx.Error(w, http.StatusInternalServerError, "internal", "no se pudo guardar la sesión")
		return
	}

	httpx.JSON(w, status, struct {
		Tokens
		User usuarioJSON `json:"user"`
	}{
		Tokens: Tokens{
			AccessToken:  access,
			RefreshToken: raw,
			ExpiresIn:    int64(h.issuer.AccessTTL().Seconds()),
		},
		User: nuevoUsuarioJSON(u),
	})
}

// usuarioJSON es lo que la app recibe junto con los tokens. Lleva el perfil de
// recuperación para que la primera pantalla después del login no tenga que
// esperar a un GET /v1/users/me para saber si enseñar el onboarding.
type usuarioJSON struct {
	ID            string           `json:"id"`
	Email         string           `json:"email"`
	DisplayName   string           `json:"displayName"`
	Role          Role             `json:"role"`
	Adicciones    []addiction.Type `json:"adicciones"`
	Principal     addiction.Type   `json:"adiccionPrincipal"`
	ConsumoDesde  *string          `json:"consumoDesde"`
	EnTratamiento bool             `json:"enTratamiento"`
	Onboarding    bool             `json:"onboardingCompleto"`
}

func nuevoUsuarioJSON(u User) usuarioJSON {
	out := usuarioJSON{
		ID:            u.ID,
		Email:         u.Email,
		DisplayName:   u.DisplayName,
		Role:          u.Role,
		Adicciones:    u.Adicciones,
		Principal:     u.Principal,
		EnTratamiento: u.EnTratamiento,
		Onboarding:    u.OnboardingCompleto(),
	}
	if out.Adicciones == nil {
		out.Adicciones = []addiction.Type{}
	}
	// Fecha sin hora: es un dato de calendario, y mandarlo con husos horarios
	// invita a que "2010-03-01" se vea como febrero en el teléfono de alguien.
	if u.ConsumoDesde != nil {
		s := u.ConsumoDesde.Format(time.DateOnly)
		out.ConsumoDesde = &s
	}
	return out
}
