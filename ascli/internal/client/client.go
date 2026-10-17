// Package client is the one place ascli speaks HTTP to the platform.
//
// Everything the CLI can do goes through here, which is what keeps three promises checkable rather
// than aspirational: the bearer token is attached in exactly one place, a refusal is never turned
// into a success, and no diagnostic output can leak the token.
package client

import (
	"bytes"
	"context"
	"crypto/tls"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net/http"
	"net/url"
	"strings"
	"time"

	"github.com/sushantadhkr/android-star/ascli/internal/config"
)

// Error is a refusal from the platform, kept structured so the CLI can exit with a code that means
// something rather than printing a number and hoping it is read.
type Error struct {
	Status  int
	Message string
	Hint    string
	// Capability is set when the platform named one, which is what makes "switched off" a
	// diagnosis rather than a guess.
	Capability string
	Available  []string
}

func (e *Error) Error() string {
	var b strings.Builder
	fmt.Fprintf(&b, "%s", e.Message)
	if e.Hint != "" {
		fmt.Fprintf(&b, "\n  %s", e.Hint)
	}
	return b.String()
}

// Exit codes. Distinct so a script can branch without parsing prose.
const (
	ExitOK       = 0
	ExitUsage    = 1
	ExitAuth     = 2
	ExitRefused  = 3 // the platform said no on purpose
	ExitNetwork  = 4
	ExitNotFound = 5
	// ExitDevice is "no such device, or several and none was named".
	ExitDevice = 6
)

// ExitCode is the code this failure should end the process with.
func (e *Error) ExitCode() int {
	switch e.Status {
	case http.StatusUnauthorized, http.StatusForbidden:
		return ExitAuth
	case http.StatusNotFound:
		return ExitNotFound
	default:
		return ExitRefused
	}
}

// Client talks to one platform.
type Client struct {
	baseURL string
	token   string
	http    *http.Client
	// redacted is the form of the token safe to put in any message.
	redacted string
	// name is the device as the user named it, for messages.
	name string
}

// New builds a client for one device.
//
// The name is carried so output and errors can say which phone something happened on: with several
// connected, "it failed" is much harder to act on than "it failed on pixel".
func New(name, baseURL, token string, skipTLSVerify bool) *Client {
	transport := http.DefaultTransport
	if skipTLSVerify {
		// Only ever on explicit request, and named in the flag's own help, so turning it on is a
		// decision someone made rather than a default they inherited.
		transport = &http.Transport{
			TLSClientConfig: &tls.Config{InsecureSkipVerify: true},
		}
	}
	return &Client{
		baseURL:  strings.TrimRight(baseURL, "/"),
		token:    token,
		name:     name,
		redacted: config.Device{Token: token}.Redacted(),
		http:     &http.Client{Transport: transport, Timeout: 60 * time.Second},
	}
}

// BaseURL is the platform this client points at.
func (c *Client) BaseURL() string { return c.baseURL }

// Name is the device as the user named it.
func (c *Client) Name() string { return c.name }

// Describe names the device for a message, falling back to its URL when it has no name.
func (c *Client) Describe() string {
	if c.name != "" {
		return c.name
	}
	return c.baseURL
}

// TokenRedacted is the token in a form that is safe to print.
func (c *Client) TokenRedacted() string { return c.redacted }

// HasToken says whether we can authenticate at all.
func (c *Client) HasToken() bool { return c.token != "" }

func (c *Client) redact(s string) string { return config.Redact(s, c.token) }

// errorFrom turns a non-2xx response into an Error, reading the platform's own explanation.
//
// The message comes from the platform rather than from us, because the platform knows why it
// refused and we would only be guessing. A body that is not the JSON we expect is still shown
// verbatim rather than replaced with something tidy.
func (c *Client) errorFrom(resp *http.Response, body []byte) *Error {
	e := &Error{Status: resp.StatusCode}

	var payload struct {
		Error      string   `json:"error"`
		Hint       string   `json:"hint"`
		Capability string   `json:"capability"`
		Available  []string `json:"available"`
	}
	if err := json.Unmarshal(body, &payload); err == nil && payload.Error != "" {
		e.Message = payload.Error
		e.Hint = payload.Hint
		e.Capability = payload.Capability
		e.Available = payload.Available
	} else {
		trimmed := strings.TrimSpace(c.redact(string(body)))
		if trimmed == "" {
			e.Message = http.StatusText(resp.StatusCode)
		} else {
			e.Message = fmt.Sprintf("HTTP %d: %s", resp.StatusCode, truncate(trimmed, 400))
		}
	}

	switch resp.StatusCode {
	case http.StatusUnauthorized:
		if e.Message == "" {
			e.Message = "Unauthorized"
		}
		if e.Hint == "" {
			e.Hint = "The token is wrong or has been rotated. Read a fresh one on the phone in Settings → API access token, then: ascli connect <url> --token <token>"
		}
	case http.StatusForbidden:
		if e.Message == "" {
			e.Message = "Forbidden"
		}
		if e.Hint == "" {
			e.Hint = "A Platform Feature is switched off. Turn it on with: ascli platform enable <FEATURE>"
		}
	}
	return e
}

func truncate(s string, max int) string {
	if len(s) <= max {
		return s
	}
	return s[:max] + "…"
}

func (c *Client) do(ctx context.Context, method, path string, body any) ([]byte, int, error) {
	var reader io.Reader
	if body != nil {
		encoded, err := json.Marshal(body)
		if err != nil {
			return nil, 0, fmt.Errorf("cannot encode the request: %w", err)
		}
		reader = bytes.NewReader(encoded)
	}

	req, err := http.NewRequestWithContext(ctx, method, c.baseURL+path, reader)
	if err != nil {
		return nil, 0, fmt.Errorf("cannot build the request: %w", err)
	}
	req.Header.Set("Accept", "application/json")
	if body != nil {
		req.Header.Set("Content-Type", "application/json")
	}
	if c.token != "" {
		req.Header.Set("Authorization", "Bearer "+c.token)
	}

	resp, err := c.http.Do(req)
	if err != nil {
		// The token can appear inside a transport-level error message when the URL carries a
		// query string, so it is scrubbed rather than trusted.
		return nil, 0, fmt.Errorf("cannot reach %s: %s", c.baseURL, c.redact(err.Error()))
	}
	defer resp.Body.Close()

	raw, err := io.ReadAll(resp.Body)
	if err != nil {
		return nil, resp.StatusCode, fmt.Errorf("the connection dropped while reading the reply: %w", err)
	}

	if resp.StatusCode < 200 || resp.StatusCode > 299 {
		return raw, resp.StatusCode, c.errorFrom(resp, raw)
	}
	return raw, resp.StatusCode, nil
}

// Docs is the platform's own description of itself: every capability, every action, every
// parameter. ascli is generated from this rather than hand-maintained, which is the only reason a
// CLI covering 113 actions can stay honest.
type Docs struct {
	Title          string           `json:"title"`
	Port           int              `json:"port"`
	Authentication string           `json:"authentication"`
	Note           string           `json:"note"`
	Endpoints      []Endpoint       `json:"endpoints"`
	Capabilities   []DocCapability  `json:"capabilities"`
	AppRoutes      []map[string]any `json:"appRoutes"`
}

// Endpoint is one raw HTTP route, for `ascli raw`.
type Endpoint struct {
	Path        string `json:"path"`
	Method      string `json:"method"`
	Description string `json:"description"`
}

// DocCapability is one Platform Feature and what it can do.
type DocCapability struct {
	ID                  string      `json:"id"`
	DisplayName         string      `json:"displayName"`
	Description         string      `json:"description"`
	Group               string      `json:"group"`
	GroupName           string      `json:"groupName"`
	Summary             string      `json:"summary"`
	RequiredPermissions []string    `json:"requiredPermissions"`
	Actions             []DocAction `json:"actions"`
	Apps                []DocAction `json:"-"`
}

// DocAction is one action, with everything needed to build a command for it.
type DocAction struct {
	Name                string     `json:"name"`
	Summary             string     `json:"summary"`
	RequiresUserConsent bool       `json:"requiresUserConsent"`
	Params              []DocParam `json:"params"`
	ExampleRequest      string     `json:"exampleRequest"`
	ExampleResponse     string     `json:"exampleResponse"`
}

// DocParam is one request field.
type DocParam struct {
	Name        string `json:"name"`
	Type        string `json:"type"`
	Required    bool   `json:"required"`
	Description string `json:"description"`
	Example     string `json:"example"`
}

// Docs fetches the platform's self-description.
func (c *Client) Docs(ctx context.Context) (*Docs, error) {
	raw, _, err := c.do(ctx, http.MethodGet, "/api/capabilities/docs", nil)
	if err != nil {
		return nil, err
	}
	docs := &Docs{}
	if err := json.Unmarshal(raw, docs); err != nil {
		return nil, fmt.Errorf("the platform sent docs that could not be read: %w", err)
	}
	return docs, nil
}

// CatalogueEntry is one capability's live state, as opposed to its documentation.
type CatalogueEntry struct {
	ID      string `json:"id"`
	Enabled bool   `json:"enabled"`
	Status  string `json:"status"`
	Group   string `json:"group"`
}

// Catalogue is the whole live picture.
type Catalogue struct {
	MasterEnabled bool              `json:"masterEnabled"`
	Capabilities  []CatalogueEntry  `json:"capabilities"`
	Apps          []json.RawMessage `json:"apps"`
}

// Catalogue reads what is switched on right now.
func (c *Client) Catalogue(ctx context.Context) (*Catalogue, error) {
	raw, _, err := c.do(ctx, http.MethodGet, "/api/capabilities", nil)
	if err != nil {
		return nil, err
	}
	cat := &Catalogue{}
	if err := json.Unmarshal(raw, cat); err != nil {
		return nil, fmt.Errorf("the platform sent a catalogue that could not be read: %w", err)
	}
	return cat, nil
}

// Invoke runs a capability action and returns the platform's own reply, untouched.
//
// Returned as bytes rather than a decoded map on purpose: re-encoding it would drop or reshape
// fields we do not know about, and the platform's answer is the one a script should see.
func (c *Client) Invoke(ctx context.Context, capability, action string, params map[string]any) ([]byte, error) {
	payload := map[string]any{"capability": capability, "action": action}
	for name, value := range params {
		payload[name] = value
	}
	raw, _, err := c.do(ctx, http.MethodPost, "/api/capabilities/invoke", payload)
	if err != nil {
		return nil, err
	}
	return raw, nil
}

// Toggle switches a capability, or the master switch when capability is empty.
func (c *Client) Toggle(ctx context.Context, capability string, enabled bool) ([]byte, error) {
	payload := map[string]any{"enabled": enabled}
	if capability != "" {
		payload["capability"] = capability
	}
	raw, _, err := c.do(ctx, http.MethodPost, "/api/capabilities/toggle", payload)
	if err != nil {
		return nil, err
	}
	return raw, nil
}

// Pull streams a file the platform produced.
//
// Streams to the writer rather than buffering, because the whole reason this exists is files that
// are too big to sit in memory.
func (c *Client) Pull(ctx context.Context, capability, id string, w io.Writer) (int64, string, error) {
	query := url.Values{}
	query.Set("capability", capability)
	query.Set("id", id)
	path := "/api/files/pull?" + query.Encode()

	req, err := http.NewRequestWithContext(ctx, http.MethodGet, c.baseURL+path, nil)
	if err != nil {
		return 0, "", fmt.Errorf("cannot build the request: %w", err)
	}
	if c.token != "" {
		req.Header.Set("Authorization", "Bearer "+c.token)
	}

	resp, err := c.http.Do(req)
	if err != nil {
		return 0, "", fmt.Errorf("cannot reach %s: %s", c.baseURL, c.redact(err.Error()))
	}
	defer resp.Body.Close()

	if resp.StatusCode < 200 || resp.StatusCode > 299 {
		raw, _ := io.ReadAll(io.LimitReader(resp.Body, 4096))
		return 0, "", c.errorFrom(resp, raw)
	}

	written, err := io.Copy(w, resp.Body)
	if err != nil {
		return written, "", fmt.Errorf("the download stopped after %d bytes: %w", written, err)
	}
	return written, fileNameFrom(resp.Header.Get("Content-Disposition")), nil
}

func fileNameFrom(disposition string) string {
	if disposition == "" {
		return ""
	}
	const marker = `filename="`
	start := strings.Index(disposition, marker)
	if start < 0 {
		return ""
	}
	rest := disposition[start+len(marker):]
	end := strings.Index(rest, `"`)
	if end < 0 {
		return ""
	}
	return rest[:end]
}

// Raw runs an arbitrary request against a documented route, for the routes with no generated
// command — an App's own endpoints, mostly.
//
// Refuses a URL rather than resolving it, so `--data @-` cannot turn into posting the token
// somewhere the user did not name.
func (c *Client) Raw(ctx context.Context, method, path string, body []byte) ([]byte, error) {
	target := path
	if !strings.HasPrefix(path, "/") {
		target = "/" + path
	}
	if strings.Contains(path, "://") {
		return nil, errors.New("give a path on this platform, not a full URL")
	}

	var reader io.Reader
	if len(body) > 0 {
		reader = bytes.NewReader(body)
	}
	req, err := http.NewRequestWithContext(ctx, method, c.baseURL+target, reader)
	if err != nil {
		return nil, fmt.Errorf("cannot build the request: %w", err)
	}
	req.Header.Set("Accept", "application/json")
	if len(body) > 0 {
		req.Header.Set("Content-Type", "application/json")
	}
	if c.token != "" {
		req.Header.Set("Authorization", "Bearer "+c.token)
	}

	resp, err := c.http.Do(req)
	if err != nil {
		return nil, fmt.Errorf("cannot reach %s: %s", c.baseURL, c.redact(err.Error()))
	}
	defer resp.Body.Close()

	raw, err := io.ReadAll(resp.Body)
	if err != nil {
		return nil, fmt.Errorf("the connection dropped while reading the reply: %w", err)
	}
	if resp.StatusCode < 200 || resp.StatusCode > 299 {
		return raw, c.errorFrom(resp, raw)
	}
	return raw, nil
}
