package ai

import (
	"context"
	"fmt"
	"strings"

	"github.com/oderflaY/Bakend-un-dia-mas/internal/risk"
)

// systemPrompt es lo que faltaba por completo en agentChat (hallazgo #2): el
// modelo no sabía con quién hablaba. El nivel del semáforo se inyecta aquí, no
// en el mensaje del usuario, para que no se pueda sobreescribir desde el prompt.
func systemPrompt(level risk.Level) string {
	var b strings.Builder
	b.WriteString(`Eres el acompañante de UnDiaMas, una app de apoyo para personas en recuperación de una adicción.
Hablas en español mexicano, en segunda persona, con calidez y sin sermones. Frases cortas.
No eres terapeuta ni médico: no diagnosticas, no recetas y no prometes resultados.
Nunca minimizas una recaída ni felicitas el consumo.
`)

	switch level {
	case risk.Red:
		b.WriteString(`
La persona está en semáforo ROJO: riesgo alto ahora mismo.
Prioriza la contención inmediata. Sugiere contactar a su contacto de confianza o a una línea de ayuda.
Si detectas riesgo de daño a sí misma o a otros, llama a guardar_alerta con nivelRiesgo "ROJO".`)
	case risk.Yellow:
		b.WriteString(`
La persona está en semáforo AMARILLO: hay señales de craving.
Ayúdale a nombrar el disparador y proponle una acción concreta de los próximos 10 minutos.`)
	default:
		b.WriteString(`
La persona está en semáforo VERDE: estable.
Refuerza lo que está funcionando sin exagerar. Conversa con normalidad.`)
	}
	return b.String()
}

// toolDeclarations: las herramientas nunca reciben el userId como parámetro.
// El servidor lo toma del token, así que el modelo no puede pedir datos ajenos
// aunque lo intente. Esto sí estaba bien en el diseño anterior y se conserva.
var toolDeclarations = []Tool{{
	FunctionDeclarations: []FunctionDeclaration{
		{
			Name:        "leer_historial_reciente",
			Description: "Devuelve los check-ins más recientes de la persona con la que estás hablando.",
			Parameters: map[string]any{
				"type": "object",
				"properties": map[string]any{
					"limite": map[string]any{
						"type":        "integer",
						"description": "Cuántos check-ins traer (1 a 10). Por defecto 5.",
					},
				},
			},
		},
		{
			Name:        "guardar_alerta",
			Description: "Registra una alerta de riesgo para que el equipo clínico la revise.",
			Parameters: map[string]any{
				"type": "object",
				"properties": map[string]any{
					"nivelRiesgo": map[string]any{
						"type": "string",
						"enum": []string{"VERDE", "AMARILLO", "ROJO"},
					},
					"mensaje": map[string]any{"type": "string"},
				},
				"required": []string{"nivelRiesgo", "mensaje"},
			},
		},
	},
}}

// ToolRunner ejecuta una herramienta en nombre del usuario autenticado.
type ToolRunner interface {
	Run(ctx context.Context, userID, name string, args map[string]any) (map[string]any, error)
}

type TurnResult struct {
	Reply         string
	SavedAlertIDs []string
}

const maxToolRounds = 3

// RunTurn orquesta la conversación.
//
// La corrección del hallazgo #3 está aquí: cuando el modelo pide una tool, el
// historial que se manda de vuelta debe conservar el turno del modelo con su
// functionCall ANTES del turno con la functionResponse. La secuencia exigida es
//
//	user → model(functionCall) → function(functionResponse) → model(texto)
//
// El cliente anterior enviaba el turno "function" suelto, sin el del modelo, y
// por eso toda respuesta que usara herramientas fallaba o alucinaba en producción.
func RunTurn(
	ctx context.Context,
	client Client,
	runner ToolRunner,
	userID string,
	level risk.Level,
	history []Content,
	prompt string,
) (TurnResult, error) {
	sys := Content{Parts: []Part{{Text: systemPrompt(level)}}}
	contents := append(append([]Content{}, history...), Content{
		Role:  "user",
		Parts: []Part{{Text: prompt}},
	})

	var result TurnResult

	for round := 0; round < maxToolRounds; round++ {
		out, err := client.Generate(ctx, generateRequest{
			Contents:          contents,
			SystemInstruction: &sys,
			Tools:             toolDeclarations,
		})
		if err != nil {
			return result, err
		}

		calls := functionCalls(out)
		if len(calls) == 0 {
			result.Reply = joinText(out)
			return result, nil
		}

		// 1) el turno del modelo, con sus functionCall intactos
		out.Role = "model"
		contents = append(contents, out)

		// 2) un turno "function" con la respuesta de cada llamada, en el mismo orden
		responses := make([]Part, 0, len(calls))
		for _, call := range calls {
			data, err := runner.Run(ctx, userID, call.Name, call.Args)
			if err != nil {
				data = map[string]any{"error": err.Error()}
			}
			if id, ok := data["alertId"].(string); ok && id != "" {
				result.SavedAlertIDs = append(result.SavedAlertIDs, id)
			}
			responses = append(responses, Part{
				FunctionResponse: &FunctionResponse{Name: call.Name, Response: data},
			})
		}
		contents = append(contents, Content{Role: "function", Parts: responses})
	}

	return result, fmt.Errorf("el modelo siguió pidiendo herramientas tras %d rondas", maxToolRounds)
}

func functionCalls(c Content) []FunctionCall {
	var out []FunctionCall
	for _, p := range c.Parts {
		if p.FunctionCall != nil {
			out = append(out, *p.FunctionCall)
		}
	}
	return out
}

func joinText(c Content) string {
	var b strings.Builder
	for _, p := range c.Parts {
		b.WriteString(p.Text)
	}
	return strings.TrimSpace(b.String())
}
