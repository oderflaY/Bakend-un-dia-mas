// Package config lee la configuración del entorno. Falla al arrancar, no en la
// primera petición: un secreto ausente es un error de despliegue, no de runtime.
package config

import (
	"fmt"
	"os"
	"strconv"
	"time"
)

type Config struct {
	Addr            string
	DatabaseURL     string
	JWTSecret       []byte
	AccessTokenTTL  time.Duration
	RefreshTokenTTL time.Duration
	GeminiAPIKey    string
	GeminiModel     string
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
