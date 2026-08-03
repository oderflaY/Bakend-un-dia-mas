// Comando api: el servidor HTTP de UnDiaMas.
package main

import (
	"context"
	"errors"
	"log/slog"
	"net/http"
	"os"
	"os/signal"
	"syscall"
	"time"

	"github.com/oderflaY/Bakend-un-dia-mas/internal/ai"
	"github.com/oderflaY/Bakend-un-dia-mas/internal/alerts"
	"github.com/oderflaY/Bakend-un-dia-mas/internal/auth"
	"github.com/oderflaY/Bakend-un-dia-mas/internal/checkins"
	"github.com/oderflaY/Bakend-un-dia-mas/internal/config"
	"github.com/oderflaY/Bakend-un-dia-mas/internal/db"
	"github.com/oderflaY/Bakend-un-dia-mas/internal/httpx"
	"github.com/oderflaY/Bakend-un-dia-mas/internal/journal"
	"github.com/oderflaY/Bakend-un-dia-mas/internal/mood"
	"github.com/oderflaY/Bakend-un-dia-mas/internal/notify"
	"github.com/oderflaY/Bakend-un-dia-mas/internal/reminders"
	"github.com/oderflaY/Bakend-un-dia-mas/internal/stats"
	"github.com/oderflaY/Bakend-un-dia-mas/internal/therapist"
	"github.com/oderflaY/Bakend-un-dia-mas/internal/tracker"
	"github.com/oderflaY/Bakend-un-dia-mas/internal/trafficlight"
	"github.com/oderflaY/Bakend-un-dia-mas/internal/users"
)

func main() {
	slog.SetDefault(slog.New(slog.NewJSONHandler(os.Stdout, nil)))
	if err := run(); err != nil {
		slog.Error("el servidor terminó con error", "err", err)
		os.Exit(1)
	}
}

func run() error {
	cfg, err := config.Load()
	if err != nil {
		return err
	}

	ctx, stop := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
	defer stop()

	pool, err := db.Open(ctx, cfg.DatabaseURL)
	if err != nil {
		return err
	}
	defer pool.Close()

	if err := db.Migrate(ctx, pool); err != nil {
		return err
	}

	issuer := auth.NewTokenIssuer(cfg.JWTSecret, cfg.AccessTokenTTL, cfg.RefreshTokenTTL)
	checkInStore := checkins.NewStore(pool)
	trackerStore := tracker.NewStore(pool)
	statsStore := stats.NewStore(pool)

	mux := http.NewServeMux()
	mux.HandleFunc("GET /healthz", func(w http.ResponseWriter, r *http.Request) {
		if err := pool.Ping(r.Context()); err != nil {
			httpx.Error(w, http.StatusServiceUnavailable, "unhealthy", "sin base de datos")
			return
		}
		httpx.JSON(w, http.StatusOK, map[string]string{"status": "ok"})
	})

	hub := notify.NewHub()
	alertSvc := alerts.NewService(pool, hub)
	lightSvc := trafficlight.NewService(pool, hub, alertSvc)
	therapistStore := therapist.NewStore(pool)

	auth.NewHandler(auth.NewStore(pool), issuer).Routes(mux)
	users.NewHandler(users.NewStore(pool), issuer).Routes(mux)
	notify.NewHandler(hub, issuer).Routes(mux)
	alerts.NewHandler(alertSvc, issuer).Routes(mux)
	tracker.NewHandler(trackerStore, issuer).Routes(mux)
	checkins.NewHandler(checkInStore, issuer, onRedCheckIn(lightSvc)).Routes(mux)
	journal.NewHandler(journal.NewStore(pool), issuer).Routes(mux)
	mood.NewHandler(mood.NewStore(pool), issuer).Routes(mux)
	trafficlight.NewHandler(lightSvc, issuer).Routes(mux)
	stats.NewHandler(statsStore, issuer).Routes(mux)
	therapist.NewHandler(therapistStore, checkInStore, trackerStore,
		lightSvc, alertSvc, statsStore, issuer).Routes(mux)

	// Los recordatorios (#8) son lo que en Firebase habría sido una onSchedule.
	// Viven en el proceso y se apagan con él.
	reminderStore := reminders.NewStore(pool)
	reminders.NewHandler(reminderStore, issuer).Routes(mux)
	go reminders.NewScheduler(reminderStore, hub).Run(ctx)

	if cfg.GeminiAPIKey == "" {
		slog.Warn("GEMINI_API_KEY vacía: /v1/ai/* queda deshabilitado")
	} else {
		ai.NewHandler(pool, ai.NewRESTClient(cfg.GeminiAPIKey, cfg.GeminiModel),
			checkInStore, alertSvc, issuer).Routes(mux)
	}

	srv := &http.Server{
		Addr:              cfg.Addr,
		Handler:           httpx.Logging(mux),
		ReadHeaderTimeout: 5 * time.Second,
		ReadTimeout:       15 * time.Second,
		WriteTimeout:      60 * time.Second, // el chat con Gemini es lo más lento
		IdleTimeout:       60 * time.Second,
	}

	errCh := make(chan error, 1)
	go func() {
		slog.Info("escuchando", "addr", cfg.Addr)
		if err := srv.ListenAndServe(); err != nil && !errors.Is(err, http.ErrServerClosed) {
			errCh <- err
		}
	}()

	select {
	case err := <-errCh:
		return err
	case <-ctx.Done():
		slog.Info("apagando")
		shutdownCtx, cancel := context.WithTimeout(context.Background(), 15*time.Second)
		defer cancel()
		return srv.Shutdown(shutdownCtx)
	}
}

// onRedCheckIn es el protocolo de emergencia: sustituye al trigger
// onCheckInCreated de Cloud Functions. Corre síncrono con la escritura del
// check-in, así que un rojo ya no puede desaparecer en silencio como pasaba
// cuando el trigger hacía return sin log (hallazgo #1).
//
// Pasa por trafficlight y no por alerts directamente para que el registro del
// semáforo, el estado del tracker y la alerta entren en la misma transacción.
func onRedCheckIn(svc *trafficlight.Service) func(context.Context, string, checkins.CheckIn) {
	return func(ctx context.Context, userID string, c checkins.CheckIn) {
		_, err := svc.Record(ctx, userID, trafficlight.Evaluation{
			Status: c.RiskLevel,
			Reason: "check-in " + c.ID,
		}, "Check-in en semáforo rojo")
		if err != nil {
			slog.Error("falló el protocolo de emergencia", "userId", userID, "err", err)
		}
	}
}
