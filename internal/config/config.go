// Package config lee la configuración del entorno. Falla al arrancar, no en la
// primera petición: un secreto ausente es un error de despliegue, no de runtime.
package config

import (
	"fmt"
	"os"
	"strconv"
	"time"

	"github.com/oderflaY/Bakend-un-dia-mas/internal/mailer"
	"github.com/oderflaY/Bakend-un-dia-mas/internal/rag"
)

type Config struct {
	Addr            string
	DatabaseURL     string
	JWTSecret       []byte
	AccessTokenTTL  time.Duration
	RefreshTokenTTL time.Duration
	GeminiAPIKey    string
	GeminiModel     string
	EmbeddingModel  string
	SMTP            mailer.Config
}

func Load() (Config, error) {
	c := Config{
		Addr:            env("ADDR", ":8080"),
		DatabaseURL:     os.Getenv("DATABASE_URL"),
		JWTSecret:       []byte(os.Getenv("JWT_SECRET")),
		AccessTokenTTL:  envDuration("ACCESS_TOKEN_TTL", 15*time.Minute),
		RefreshTokenTTL: envDuration("REFRESH_TOKEN_TTL", 30*24*time.Hour),
		GeminiAPIKey:    os.Getenv("GEMINI_API_KEY"),
		GeminiModel:     env("GEMINI_MODEL", "gemini-2.5-flash"),
		// El modelo de embeddings es otro y se factura aparte del de chat.
		EmbeddingModel: env("EMBEDDING_MODEL", rag.ModeloPorDefecto),

		// Correo saliente, solo para recuperar contraseñas. Sin SMTP_HOST el
		// servidor arranca igual y esas dos rutas responden 503.
		SMTP: mailer.Config{
			Host: os.Getenv("SMTP_HOST"),
			// 587 es STARTTLS, que es lo que ofrece casi todo el mundo. El 465
			// (TLS directo) también funciona: lo detecta el mailer por el número.
			Port:     envInt("SMTP_PORT", 587),
			User:     os.Getenv("SMTP_USER"),
			Pass:     os.Getenv("SMTP_PASSWORD"),
			From:     os.Getenv("SMTP_FROM"),
			FromName: env("SMTP_FROM_NAME", "Un Día Más"),
		},
	}

	if c.DatabaseURL == "" {
		return c, fmt.Errorf("falta DATABASE_URL")
	}
	if len(c.JWTSecret) < 32 {
		return c, fmt.Errorf("JWT_SECRET debe tener al menos 32 bytes")
	}
	return c, nil
}

func env(key, def string) string {
	if v := os.Getenv(key); v != "" {
		return v
	}
	return def
}

func envInt(key string, def int) int {
	if v, err := strconv.Atoi(os.Getenv(key)); err == nil && v > 0 {
		return v
	}
	return def
}

func envDuration(key string, def time.Duration) time.Duration {
	v := os.Getenv(key)
	if v == "" {
		return def
	}
	if d, err := time.ParseDuration(v); err == nil {
		return d
	}
	if secs, err := strconv.Atoi(v); err == nil {
		return time.Duration(secs) * time.Second
	}
	return def
}
