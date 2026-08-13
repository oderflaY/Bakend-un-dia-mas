// Package mailer manda correo por SMTP. Es todo lo que el backend necesita:
// un mensaje de texto a una dirección, sin plantillas ni colas.
//
// Se usa SMTP genérico y no la API de un proveedor concreto a propósito: sirve
// con Gmail, Zoho, el SMTP del hosting o el de una empresa, y cambiar de sitio
// es cambiar cuatro variables del .env en vez de reescribir código. Con Gmail
// hace falta una "contraseña de aplicación": la normal la rechaza el servidor.
//
// Sin SMTP_HOST configurado, Enviar devuelve ErrSinConfigurar y quien llama
// decide qué hacer. Es el mismo trato que con GEMINI_API_KEY: la ausencia de
// una integración opcional apaga esa función, no impide arrancar el servidor.
package mailer

import (
	"crypto/tls"
	"errors"
	"fmt"
	"mime"
	"net"
	"net/smtp"
	"strings"
	"time"
)

var ErrSinConfigurar = errors.New("no hay SMTP configurado")

type Config struct {
	Host string
	Port int
	User string
	Pass string
	// From es el remitente. Si va vacío se usa User, que es lo que casi siempre
	// quiere decir la gente al configurarlo.
	From string
	// FromName es el nombre visible. Sin él, el correo llega firmado por una
	// dirección a secas, que en un mensaje de recuperación parece phishing.
	FromName string
}

type Mailer struct{ cfg Config }

func New(cfg Config) *Mailer {
	if cfg.From == "" {
		cfg.From = cfg.User
	}
	return &Mailer{cfg: cfg}
}

// Configurado dice si hay a dónde mandar. Las rutas que dependen del correo se
// apagan cuando esto es falso.
func (m *Mailer) Configurado() bool {
	return m != nil && m.cfg.Host != "" && m.cfg.From != ""
}

// Enviar manda un mensaje de texto plano.
//
// La conexión se abre y se cierra por mensaje. Con el volumen de esta app
// —recuperaciones de contraseña, nada más— mantener una sesión SMTP viva sería
// complicar el código para ahorrar un handshake que ocurre unas pocas veces al
// día.
func (m *Mailer) Enviar(para, asunto, cuerpo string) error {
	if !m.Configurado() {
		return ErrSinConfigurar
	}

	addr := net.JoinHostPort(m.cfg.Host, fmt.Sprint(m.cfg.Port))
	conn, err := dial(addr, m.cfg.Host, m.cfg.Port)
	if err != nil {
		return fmt.Errorf("conectar con %s: %w", addr, err)
	}

	c, err := smtp.NewClient(conn, m.cfg.Host)
	if err != nil {
		conn.Close()
		return err
	}
	defer c.Close()

	// En el puerto 587 la conexión nace en claro y se cifra con STARTTLS. Si el
	// servidor no lo ofrece, se aborta: mandar la contraseña SMTP por un canal
	// sin cifrar sería peor que no mandar el correo.
	if m.cfg.Port != 465 {
		ok, _ := c.Extension("STARTTLS")
		if !ok {
			return errors.New("el servidor SMTP no ofrece STARTTLS; no se manda nada en claro")
		}
		if err := c.StartTLS(&tls.Config{ServerName: m.cfg.Host}); err != nil {
			return err
		}
	}

	if m.cfg.Pass != "" {
		if err := c.Auth(smtp.PlainAuth("", m.cfg.User, m.cfg.Pass, m.cfg.Host)); err != nil {
			return fmt.Errorf("autenticar en SMTP: %w", err)
		}
	}
	if err := c.Mail(m.cfg.From); err != nil {
		return err
	}
	if err := c.Rcpt(para); err != nil {
		return err
	}

	w, err := c.Data()
	if err != nil {
		return err
	}
	if _, err := w.Write([]byte(m.mensaje(para, asunto, cuerpo))); err != nil {
		return err
	}
	if err := w.Close(); err != nil {
		return err
	}
	return c.Quit()
}

// dial abre la conexión según el puerto: 465 nace cifrado, el resto en claro
// para subir a TLS después con STARTTLS.
func dial(addr, host string, port int) (net.Conn, error) {
	d := &net.Dialer{Timeout: 15 * time.Second}
	if port == 465 {
		return tls.DialWithDialer(d, "tcp", addr, &tls.Config{ServerName: host})
	}
	return d.Dial("tcp", addr)
}

// mensaje arma las cabeceras a mano. El asunto va codificado en RFC 2047 porque
// los acentos son inevitables en español y sin eso llegan rotos.
func (m *Mailer) mensaje(para, asunto, cuerpo string) string {
	de := m.cfg.From
	if m.cfg.FromName != "" {
		de = mime.QEncoding.Encode("utf-8", m.cfg.FromName) + " <" + m.cfg.From + ">"
	}

	var b strings.Builder
	b.WriteString("From: " + de + "\r\n")
	b.WriteString("To: " + para + "\r\n")
	b.WriteString("Subject: " + mime.QEncoding.Encode("utf-8", asunto) + "\r\n")
	b.WriteString("Date: " + time.Now().Format(time.RFC1123Z) + "\r\n")
	b.WriteString("MIME-Version: 1.0\r\n")
	b.WriteString("Content-Type: text/plain; charset=utf-8\r\n")
	b.WriteString("\r\n")
	// Normalizar los saltos: SMTP espera CRLF, y el cuerpo se escribe en Go con
	// \n a secas.
	b.WriteString(strings.ReplaceAll(cuerpo, "\n", "\r\n"))
	return b.String()
}
