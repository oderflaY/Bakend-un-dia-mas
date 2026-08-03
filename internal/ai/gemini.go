package ai

import (
	"bytes"
	"context"
	"encoding/json"
	"fmt"
	"net/http"
	"net/url"
	"time"
)

// Tipos del cuerpo REST de Gemini (generateContent).
type Content struct {
	Role  string `json:"role"` // "user" | "model" | "function"
	Parts []Part `json:"parts"`
}

type Part struct {
	Text             string            `json:"text,omitempty"`
	FunctionCall     *FunctionCall     `json:"functionCall,omitempty"`
	FunctionResponse *FunctionResponse `json:"functionResponse,omitempty"`
}

type FunctionCall struct {
	Name string         `json:"name"`
	Args map[string]any `json:"args,omitempty"`
}

type FunctionResponse struct {
	Name     string         `json:"name"`
	Response map[string]any `json:"response"`
}

type Tool struct {
	FunctionDeclarations []FunctionDeclaration `json:"functionDeclarations"`
}

type FunctionDeclaration struct {
	Name        string `json:"name"`
	Description string `json:"description"`
	Parameters  any    `json:"parameters,omitempty"`
}

type generateRequest struct {
	Contents          []Content `json:"contents"`
	SystemInstruction *Content  `json:"systemInstruction,omitempty"`
	Tools             []Tool    `json:"tools,omitempty"`
	GenerationConfig  any       `json:"generationConfig,omitempty"`
}

type generateResponse struct {
	Candidates []struct {
		Content Content `json:"content"`
	} `json:"candidates"`
	PromptFeedback *struct {
		BlockReason string `json:"blockReason"`
	} `json:"promptFeedback,omitempty"`
}

// Client es la interfaz que consume el orquestador. Existe para poder testear
// runTurn con un doble; el bug #3 del backend anterior se coló justamente porque
// el mock ocultaba la forma real del cuerpo.
type Client interface {
	Generate(ctx context.Context, req generateRequest) (Content, error)
}

type RESTClient struct {
	APIKey string
	Model  string
	HTTP   *http.Client
}

func NewRESTClient(apiKey, model string) *RESTClient {
	return &RESTClient{
		APIKey: apiKey,
		Model:  model,
		HTTP:   &http.Client{Timeout: 30 * time.Second},
	}
}

func (c *RESTClient) Generate(ctx context.Context, req generateRequest) (Content, error) {
	body, err := json.Marshal(req)
	if err != nil {
		return Content{}, err
	}

	endpoint := fmt.Sprintf(
		"https://generativelanguage.googleapis.com/v1beta/models/%s:generateContent",
		url.PathEscape(c.Model))

	httpReq, err := http.NewRequestWithContext(ctx, http.MethodPost, endpoint, bytes.NewReader(body))
	if err != nil {
		return Content{}, err
	}
	httpReq.Header.Set("Content-Type", "application/json")
	httpReq.Header.Set("x-goog-api-key", c.APIKey)

	resp, err := c.HTTP.Do(httpReq)
	if err != nil {
		return Content{}, err
	}
	defer resp.Body.Close()

	if resp.StatusCode != http.StatusOK {
		var buf bytes.Buffer
		_, _ = buf.ReadFrom(resp.Body)
		return Content{}, fmt.Errorf("gemini respondió %d: %s", resp.StatusCode, buf.String())
	}

	var out generateResponse
	if err := json.NewDecoder(resp.Body).Decode(&out); err != nil {
		return Content{}, err
	}
	if out.PromptFeedback != nil && out.PromptFeedback.BlockReason != "" {
		return Content{}, fmt.Errorf("gemini bloqueó la petición: %s", out.PromptFeedback.BlockReason)
	}
	if len(out.Candidates) == 0 {
		return Content{}, fmt.Errorf("gemini no devolvió candidatos")
	}
	return out.Candidates[0].Content, nil
}
