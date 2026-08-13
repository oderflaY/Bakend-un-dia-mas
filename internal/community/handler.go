package community

import (
	"context"
	"errors"
	"net/http"
	"strings"

	"github.com/oderflaY/Bakend-un-dia-mas/internal/auth"
	"github.com/oderflaY/Bakend-un-dia-mas/internal/httpx"
)

type Handler struct {
	store  *Store
	issuer *auth.TokenIssuer
}

func NewHandler(store *Store, issuer *auth.TokenIssuer) *Handler {
	return &Handler{store: store, issuer: issuer}
}

func (h *Handler) Routes(mux *http.ServeMux) {
	m := h.issuer.Middleware
	mux.Handle("GET /v1/community/me", m(http.HandlerFunc(h.perfil)))
	mux.Handle("PUT /v1/community/me", m(http.HandlerFunc(h.putAlias)))
	mux.Handle("GET /v1/community/stories", m(http.HandlerFunc(h.listar)))
	mux.Handle("POST /v1/community/stories", m(http.HandlerFunc(h.publicar)))
	mux.Handle("DELETE /v1/community/stories/{id}", m(http.HandlerFunc(h.borrar)))
	mux.Handle("PUT /v1/community/stories/{id}/useful", m(http.HandlerFunc(h.util)))
	mux.Handle("POST /v1/community/stories/{id}/reports", m(http.HandlerFunc(h.reportar)))
	mux.Handle("POST /v1/community/stories/{id}/block-author", m(http.HandlerFunc(h.bloquear)))

	// La cola de moderación. Rol admin, que no se puede pedir al registrarse.
	admin := func(f http.HandlerFunc) http.Handler {
		return m(auth.RequireRole(auth.RoleAdmin, f))
	}
	mux.Handle("GET /v1/admin/moderation/stories", admin(h.cola))
	mux.Handle("POST /v1/admin/moderation/stories/{id}/approve", admin(h.aprobar))
	mux.Handle("POST /v1/admin/moderation/stories/{id}/remove", admin(h.retirar))
}

func (h *Handler) cola(w http.ResponseWriter, r *http.Request) {
	lista, err := h.store.Cola(r.Context(), httpx.Limit(r, 50, 200))
	if err != nil {
		httpx.Error(w, http.StatusInternalServerError, "internal", "no se pudo leer la cola")
		return
	}
	httpx.JSON(w, http.StatusOK, map[string]any{
		"pendientes": len(lista),
		"historias":  lista,
	})
}

func (h *Handler) aprobar(w http.ResponseWriter, r *http.Request) {
	h.moderar(w, r, h.store.Aprobar, "aprobada")
}

func (h *Handler) retirar(w http.ResponseWriter, r *http.Request) {
	h.moderar(w, r, h.store.Retirar, "retirada")
}

// moderar es el tronco de las dos decisiones: se diferencian en una llamada al
// store y en la palabra que devuelven.
func (h *Handler) moderar(w http.ResponseWriter, r *http.Request,
	accion func(ctx context.Context, moderadorID, storyID, motivo string) error, resultado string) {
	id := auth.MustFrom(r.Context())

	var in struct {
		Motivo string `json:"motivo"`
	}
	if err := httpx.Decode(w, r, &in); err != nil {
		httpx.Error(w, http.StatusBadRequest, "invalid-argument", err.Error())
		return
	}
	// El motivo es obligatorio en las dos direcciones. Una decisión de
	// moderación sin razón escrita no se puede revisar después, y "¿por qué se
	// retiró mi historia?" es una pregunta que alguien va a hacer.
	in.Motivo = strings.TrimSpace(in.Motivo)
	if in.Motivo == "" {
		httpx.Error(w, http.StatusBadRequest, "invalid-argument", "escribe el motivo de la decisión")
		return
	}
	if len([]rune(in.Motivo)) > maxDetalle {
		httpx.Error(w, http.StatusBadRequest, "invalid-argument", "el motivo es demasiado largo")
		return
	}

	err := accion(r.Context(), id.UserID, r.PathValue("id"), in.Motivo)
	switch {
	case errors.Is(err, ErrNoEncontrada):
		httpx.Error(w, http.StatusNotFound, "not-found", "no existe esa historia")
		return
	case errors.Is(err, ErrNoEnRevision):
		// 409 y no 400: la petición era válida, pero alguien llegó antes.
		httpx.Error(w, http.StatusConflict, "not-in-review", "esa historia ya no está en revisión")
		return
	case err != nil:
		httpx.Error(w, http.StatusInternalServerError, "internal", "no se pudo moderar")
		return
	}
	httpx.JSON(w, http.StatusOK, map[string]any{"resultado": resultado})
}

func (h *Handler) perfil(w http.ResponseWriter, r *http.Request) {
	id := auth.MustFrom(r.Context())
	p, err := h.store.Perfil(r.Context(), id.UserID)
	if err != nil {
		httpx.Error(w, http.StatusInternalServerError, "internal", "no se pudo leer tu perfil de comunidad")
		return
	}
	httpx.JSON(w, http.StatusOK, p)
}

func (h *Handler) putAlias(w http.ResponseWriter, r *http.Request) {
	id := auth.MustFrom(r.Context())

	var in struct {
		Alias string `json:"alias"`
	}
	if err := httpx.Decode(w, r, &in); err != nil {
		httpx.Error(w, http.StatusBadRequest, "invalid-argument", err.Error())
		return
	}

	p, err := h.store.GuardarAlias(r.Context(), id.UserID, in.Alias)
	switch {
	case errors.Is(err, ErrAliasInvalido):
		httpx.Error(w, http.StatusBadRequest, "invalid-argument", err.Error())
		return
	case errors.Is(err, ErrAliasTomado):
		// 409 y no 400: el dato es válido, solo que ya es de alguien.
		httpx.Error(w, http.StatusConflict, "alias-taken", err.Error())
		return
	case err != nil:
		httpx.Error(w, http.StatusInternalServerError, "internal", "no se pudo guardar el alias")
		return
	}
	httpx.JSON(w, http.StatusOK, p)
}

func (h *Handler) listar(w http.ResponseWriter, r *http.Request) {
	id := auth.MustFrom(r.Context())
	q := r.URL.Query()

	page, err := h.store.Listar(r.Context(), id.UserID,
		ParseOrden(q.Get("sort")), httpx.Limit(r, 20, 50), q.Get("cursor"))
	if err != nil {
		if strings.Contains(err.Error(), "cursor") {
			httpx.Error(w, http.StatusBadRequest, "invalid-argument", "cursor inválido")
			return
		}
		httpx.Error(w, http.StatusInternalServerError, "internal", "no se pudo leer el muro")
		return
	}
	httpx.JSON(w, http.StatusOK, page)
}

func (h *Handler) publicar(w http.ResponseWriter, r *http.Request) {
	id := auth.MustFrom(r.Context())

	var in struct {
		Titulo         string `json:"titulo"`
		Cuerpo         string `json:"cuerpo"`
		Objetivo       string `json:"objetivo"`
		CompartirRacha bool   `json:"compartirRacha"`
	}
	if err := httpx.Decode(w, r, &in); err != nil {
		httpx.Error(w, http.StatusBadRequest, "invalid-argument", err.Error())
		return
	}

	in.Titulo = strings.TrimSpace(in.Titulo)
	in.Cuerpo = strings.TrimSpace(in.Cuerpo)
	in.Objetivo = strings.TrimSpace(in.Objetivo)

	if msg := valida(in.Titulo, in.Cuerpo, in.Objetivo); msg != "" {
		httpx.Error(w, http.StatusBadRequest, "invalid-argument", msg)
		return
	}

	// El filtro de contenido va antes de escribir: lo que se rechaza no llega a
	// existir. Código propio ("unsafe-content") para que la app pueda enseñar el
	// mensaje del servidor tal cual en vez de un error genérico.
	if motivo, msg := Revisar(in.Titulo, in.Cuerpo, in.Objetivo); motivo != "" {
		httpx.JSON(w, http.StatusUnprocessableEntity, map[string]any{
			"error": "unsafe-content", "motivo": motivo, "message": msg,
		})
		return
	}

	st, err := h.store.Publicar(r.Context(), id.UserID, NuevaStory{
		Titulo: in.Titulo, Cuerpo: in.Cuerpo,
		Objetivo: in.Objetivo, CompartirRacha: in.CompartirRacha,
	})
	switch {
	case errors.Is(err, ErrSinAlias):
		httpx.Error(w, http.StatusConflict, "alias-required", err.Error())
		return
	case errors.Is(err, ErrNoElegible):
		httpx.Error(w, http.StatusForbidden, "not-eligible", err.Error())
		return
	case err != nil:
		httpx.Error(w, http.StatusInternalServerError, "internal", "no se pudo publicar")
		return
	}

	// Los avisos van CON la historia ya publicada, no en lugar de ella: avisan,
	// no bloquean. La app los enseña después de guardar por si quiere borrarla.
	httpx.JSON(w, http.StatusCreated, struct {
		Story
		Avisos []Aviso `json:"avisos"`
	}{st, Avisos(in.Titulo, in.Cuerpo, in.Objetivo)})
}

func (h *Handler) borrar(w http.ResponseWriter, r *http.Request) {
	id := auth.MustFrom(r.Context())
	err := h.store.Borrar(r.Context(), id.UserID, r.PathValue("id"))
	if errors.Is(err, ErrNoEncontrada) {
		httpx.Error(w, http.StatusNotFound, "not-found", "no existe esa historia")
		return
	}
	if err != nil {
		httpx.Error(w, http.StatusInternalServerError, "internal", "no se pudo borrar")
		return
	}
	w.WriteHeader(http.StatusNoContent)
}

func (h *Handler) util(w http.ResponseWriter, r *http.Request) {
	id := auth.MustFrom(r.Context())

	var in struct {
		Util *bool `json:"util"`
	}
	if err := httpx.Decode(w, r, &in); err != nil || in.Util == nil {
		httpx.Error(w, http.StatusBadRequest, "invalid-argument", `se espera {"util": true|false}`)
		return
	}

	total, err := h.store.MarcarUtil(r.Context(), id.UserID, r.PathValue("id"), *in.Util)
	if errors.Is(err, ErrNoEncontrada) {
		httpx.Error(w, http.StatusNotFound, "not-found", "no existe esa historia")
		return
	}
	if err != nil {
		httpx.Error(w, http.StatusInternalServerError, "internal", "no se pudo registrar")
		return
	}
	httpx.JSON(w, http.StatusOK, map[string]any{"utiles": total, "meAyudo": *in.Util})
}

// motivos válidos de reporte. Cerrado a propósito: una lista libre acabaría
// siendo un campo de texto que nadie lee.
var motivos = map[string]bool{
	"contenido-peligroso": true,
	"datos-personales":    true,
	"acoso":               true,
	"spam":                true,
	"otro":                true,
}

func (h *Handler) reportar(w http.ResponseWriter, r *http.Request) {
	id := auth.MustFrom(r.Context())

	var in struct {
		Motivo  string `json:"motivo"`
		Detalle string `json:"detalle"`
	}
	if err := httpx.Decode(w, r, &in); err != nil {
		httpx.Error(w, http.StatusBadRequest, "invalid-argument", err.Error())
		return
	}
	in.Motivo = strings.ToLower(strings.TrimSpace(in.Motivo))
	if !motivos[in.Motivo] {
		httpx.Error(w, http.StatusBadRequest, "invalid-argument",
			"motivo debe ser contenido-peligroso, datos-personales, acoso, spam u otro")
		return
	}
	if len([]rune(in.Detalle)) > maxDetalle {
		httpx.Error(w, http.StatusBadRequest, "invalid-argument", "el detalle es demasiado largo")
		return
	}

	estado, err := h.store.Reportar(r.Context(), id.UserID, r.PathValue("id"), in.Motivo, in.Detalle)
	switch {
	case errors.Is(err, ErrNoEncontrada):
		httpx.Error(w, http.StatusNotFound, "not-found", "no existe esa historia")
		return
	case errors.Is(err, ErrPropia):
		httpx.Error(w, http.StatusBadRequest, "invalid-argument", "no puedes reportar tu propia historia")
		return
	case err != nil:
		httpx.Error(w, http.StatusInternalServerError, "internal", "no se pudo reportar")
		return
	}

	// Se devuelve el estado para que la app pueda decir "ya está en revisión" en
	// vez de un "gracias" mudo. No se devuelve cuántos reportes lleva: eso le
	// diría a cualquiera lo cerca que está de tumbar una historia.
	httpx.JSON(w, http.StatusCreated, map[string]any{
		"reportada":  true,
		"estado":     estado,
		"enRevision": estado == EnRevision,
	})
}

func (h *Handler) bloquear(w http.ResponseWriter, r *http.Request) {
	id := auth.MustFrom(r.Context())
	err := h.store.BloquearAutor(r.Context(), id.UserID, r.PathValue("id"))
	switch {
	case errors.Is(err, ErrNoEncontrada):
		httpx.Error(w, http.StatusNotFound, "not-found", "no existe esa historia")
		return
	case errors.Is(err, ErrPropia):
		httpx.Error(w, http.StatusBadRequest, "invalid-argument", "no puedes bloquearte a ti misma")
		return
	case err != nil:
		httpx.Error(w, http.StatusInternalServerError, "internal", "no se pudo bloquear")
		return
	}
	httpx.JSON(w, http.StatusOK, map[string]any{"bloqueado": true})
}

// valida los tamaños. El mínimo del cuerpo existe porque el muro no es un chat:
// una historia de dos líneas no le sirve a quien la lee y satura el orden por
// recientes.
func valida(titulo, cuerpo, objetivo string) string {
	switch {
	case titulo == "":
		return "el título no puede estar vacío"
	case len([]rune(titulo)) > maxTitulo:
		return "el título es demasiado largo"
	case len([]rune(cuerpo)) < minCuerpo:
		return "cuenta un poco más: al menos 80 caracteres"
	case len([]rune(cuerpo)) > maxCuerpo:
		return "la historia es demasiado larga"
	case len([]rune(objetivo)) > maxObjetivo:
		return "el objetivo es demasiado largo"
	}
	return ""
}
