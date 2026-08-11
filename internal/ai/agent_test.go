package ai

import (
	"context"
	"strings"
	"testing"

	"github.com/oderflaY/Bakend-un-dia-mas/internal/risk"
)

// fakeClient guarda los cuerpos que recibe: el test se hace sobre la forma real
// de la petición, no sobre un mock que la esconde. Ese fue el punto ciego del
// backend anterior (hallazgo #3).
type fakeClient struct {
	requests  []generateRequest
	responses []Content
}

func (f *fakeClient) Generate(_ context.Context, req generateRequest) (Content, error) {
	f.requests = append(f.requests, req)
	out := f.responses[0]
	f.responses = f.responses[1:]
	return out, nil
}

type fakeRunner struct{ calls []string }

func (r *fakeRunner) Run(_ context.Context, _, name string, _ map[string]any) (map[string]any, error) {
	r.calls = append(r.calls, name)
	return map[string]any{"alertId": "alerta-1"}, nil
}

func TestRunTurnConservaElTurnoDelModeloAntesDeLaRespuestaDeLaTool(t *testing.T) {
	client := &fakeClient{responses: []Content{
		{Role: "model", Parts: []Part{{FunctionCall: &FunctionCall{
			Name: "guardar_alerta",
			Args: map[string]any{"nivelRiesgo": "ROJO", "mensaje": "necesita apoyo"},
		}}}},
		{Role: "model", Parts: []Part{{Text: "Estoy aquí contigo."}}},
	}}
	runner := &fakeRunner{}

	res, err := RunTurn(context.Background(), client, runner, nil, "u1", risk.Red, nil, "no puedo más")
	if err != nil {
		t.Fatalf("error inesperado: %v", err)
	}
	if res.Reply != "Estoy aquí contigo." {
		t.Errorf("reply = %q", res.Reply)
	}
	if len(res.SavedAlertIDs) != 1 || res.SavedAlertIDs[0] != "alerta-1" {
		t.Errorf("alertas = %v", res.SavedAlertIDs)
	}
	if len(client.requests) != 2 {
		t.Fatalf("se esperaban 2 llamadas a Gemini, hubo %d", len(client.requests))
	}

	// La secuencia exigida por la API: user → model(functionCall) → function(functionResponse)
	got := client.requests[1].Contents
	want := []string{"user", "model", "function"}
	if len(got) != len(want) {
		t.Fatalf("el segundo turno tiene %d contenidos, se esperaban %d", len(got), len(want))
	}
	for i, role := range want {
		if got[i].Role != role {
			t.Errorf("contents[%d].Role = %q, se esperaba %q", i, got[i].Role, role)
		}
	}
	if got[1].Parts[0].FunctionCall == nil {
		t.Error("el turno del modelo perdió su functionCall")
	}
	if got[2].Parts[0].FunctionResponse == nil {
		t.Error("falta la functionResponse")
	}
}

func TestSystemPromptCambiaConElSemaforo(t *testing.T) {
	if !strings.Contains(systemPrompt(risk.Red), "ROJO") {
		t.Error("el prompt de rojo no menciona el nivel")
	}
	if strings.Contains(systemPrompt(risk.Green), "contacto de confianza") {
		t.Error("en verde no debe empujarse el protocolo de emergencia")
	}
}

func TestRunTurnCortaSiElModeloNoDejaDePedirTools(t *testing.T) {
	call := Content{Role: "model", Parts: []Part{{FunctionCall: &FunctionCall{Name: "leer_historial_reciente"}}}}
	client := &fakeClient{responses: []Content{call, call, call}}

	if _, err := RunTurn(context.Background(), client, &fakeRunner{}, nil, "u1", risk.Green, nil, "hola"); err == nil {
		t.Fatal("se esperaba error por exceso de rondas")
	}
}

// fakeRetriever no toca red ni corpus: aquí solo se comprueba dónde aterriza el
// material, que es lo que importa a nivel de agente.
type fakeRetriever struct {
	material string
	consulta string
	level    risk.Level
}

func (f *fakeRetriever) Prompt(_ context.Context, consulta string, level risk.Level) string {
	f.consulta, f.level = consulta, level
	return f.material
}

func TestRunTurnMeteElMaterialEnLaInstruccionDeSistema(t *testing.T) {
	client := &fakeClient{responses: []Content{
		{Role: "model", Parts: []Part{{Text: "ok"}}},
	}}
	ret := &fakeRetriever{material: "[1] El antojo sube y baja solo"}

	if _, err := RunTurn(context.Background(), client, &fakeRunner{}, ret,
		"u1", risk.Yellow, nil, "tengo muchas ganas de fumar"); err != nil {
		t.Fatalf("error inesperado: %v", err)
	}

	sys := client.requests[0].SystemInstruction
	if sys == nil {
		t.Fatal("no se mandó instrucción de sistema")
	}
	if !strings.Contains(sys.Parts[0].Text, ret.material) {
		t.Error("el material recuperado no llegó a la instrucción de sistema")
	}
	// En el turno del usuario no: si fuera ahí, un prompt podría contradecirlo o
	// pedirle al modelo que lo ignore.
	user := client.requests[0].Contents[0]
	if strings.Contains(user.Parts[0].Text, ret.material) {
		t.Error("el material se coló en el turno del usuario")
	}
	if ret.consulta != "tengo muchas ganas de fumar" || ret.level != risk.Yellow {
		t.Errorf("el recuperador recibió (%q, %v)", ret.consulta, ret.level)
	}
}

func TestRunTurnFuncionaSinRecuperador(t *testing.T) {
	client := &fakeClient{responses: []Content{{Role: "model", Parts: []Part{{Text: "aquí estoy"}}}}}
	res, err := RunTurn(context.Background(), client, &fakeRunner{}, nil, "u1", risk.Green, nil, "hola")
	if err != nil {
		t.Fatalf("sin RAG el chat debe seguir funcionando: %v", err)
	}
	if res.Reply != "aquí estoy" {
		t.Errorf("reply = %q", res.Reply)
	}
}
