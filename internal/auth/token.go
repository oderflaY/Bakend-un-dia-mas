package auth

import (
	"crypto/rand"
	"crypto/sha256"
	"encoding/base64"
	"fmt"
	"time"

	"github.com/golang-jwt/jwt/v5"
)

// Role viaja dentro del access token. Esto es lo que sustituye a esTerapeuta()
// de firestore.rules (hallazgo #16): autorizar ya no cuesta una lectura a la BD.
type Role string

const (
	RolePatient   Role = "patient"
	RoleTherapist Role = "therapist"
	// RoleAdmin atiende la cola de moderación del muro. No se puede pedir al
	// registrarse —CreateUser siempre inserta 'patient'—: se concede a mano en
	// la base, y eso es deliberado. Es el único rol que ve identificadores de
	// autores, así que darlo tiene que costar entrar al servidor.
	RoleAdmin Role = "admin"
)

type Claims struct {
	Role Role `json:"role"`
	jwt.RegisteredClaims
}

type Tokens struct {
	AccessToken  string `json:"accessToken"`
	RefreshToken string `json:"refreshToken"`
	ExpiresIn    int64  `json:"expiresIn"` // segundos de vida del access token
}

type TokenIssuer struct {
	secret     []byte
	accessTTL  time.Duration
	refreshTTL time.Duration
}

func NewTokenIssuer(secret []byte, accessTTL, refreshTTL time.Duration) *TokenIssuer {
	return &TokenIssuer{secret: secret, accessTTL: accessTTL, refreshTTL: refreshTTL}
}

func (t *TokenIssuer) AccessTTL() time.Duration  { return t.accessTTL }
func (t *TokenIssuer) RefreshTTL() time.Duration { return t.refreshTTL }

func (t *TokenIssuer) NewAccessToken(userID string, role Role) (string, error) {
	now := time.Now()
	claims := Claims{
		Role: role,
		RegisteredClaims: jwt.RegisteredClaims{
			Subject:   userID,
			IssuedAt:  jwt.NewNumericDate(now),
			ExpiresAt: jwt.NewNumericDate(now.Add(t.accessTTL)),
		},
	}
	return jwt.NewWithClaims(jwt.SigningMethodHS256, claims).SignedString(t.secret)
}

func (t *TokenIssuer) Parse(raw string) (*Claims, error) {
	var claims Claims
	_, err := jwt.ParseWithClaims(raw, &claims, func(tok *jwt.Token) (any, error) {
		if _, ok := tok.Method.(*jwt.SigningMethodHMAC); !ok {
			return nil, fmt.Errorf("algoritmo inesperado: %v", tok.Header["alg"])
		}
		return t.secret, nil
	}, jwt.WithValidMethods([]string{jwt.SigningMethodHS256.Alg()}))
	if err != nil {
		return nil, err
	}
	return &claims, nil
}

// NewRefreshToken devuelve el token en claro (va al cliente) y su hash SHA-256
// (lo único que se guarda). Un volcado de la tabla no permite suplantar a nadie.
func NewRefreshToken() (raw, hash string, err error) {
	b := make([]byte, 32)
	if _, err = rand.Read(b); err != nil {
		return "", "", err
	}
	raw = base64.RawURLEncoding.EncodeToString(b)
	return raw, HashToken(raw), nil
}

func HashToken(raw string) string {
	sum := sha256.Sum256([]byte(raw))
	return base64.RawURLEncoding.EncodeToString(sum[:])
}
