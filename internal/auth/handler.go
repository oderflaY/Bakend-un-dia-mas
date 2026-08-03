package auth

import (
	"errors"
	"net/http"
	"net/mail"
	"strings"
	"time"

	"golang.org/x/crypto/bcrypt"

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

	hash, err := bcrypt.GenerateFromPassword([]byte(in.Password), bcrypt.DefaultCost)
	if err != nil {
		httpx.Error(w, http.StatusInternalServerError, "internal", "no se pudo procesar la contraseña")
		return
	}

	// El rol no se acepta del cuerpo en ningún caso: es el equivalente de
	// noTocaRol() en firestore.rules. CreateUser siempre inserta 'patient'.
	u, err := h.store.CreateUser(r.Context(), strings.TrimSpace(in.Email), string(hash), in.DisplayName)
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
		User struct {
			ID          string `json:"id"`
			Email       string `json:"email"`
			DisplayName string `json:"displayName"`
			Role        Role   `json:"role"`
		} `json:"user"`
	}{
		Tokens: Tokens{
			AccessToken:  access,
			RefreshToken: raw,
			ExpiresIn:    int64(h.issuer.AccessTTL().Seconds()),
		},
		User: struct {
			ID          string `json:"id"`
			Email       string `json:"email"`
			DisplayName string `json:"displayName"`
			Role        Role   `json:"role"`
		}{u.ID, u.Email, u.DisplayName, u.Role},
	})
}
