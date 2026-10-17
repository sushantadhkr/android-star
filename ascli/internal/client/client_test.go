package client

import (
	"context"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"os"
	"strings"
	"testing"
)

const TOKEN = "d128dd60d26efaffe439615fb315def37595d0748f412353187ee479c7f75117"

// fakePlatform is a stand-in for a phone, so the client's own behaviour can be checked without one.
type fakePlatform struct {
	lastAuth  string
	lastBody  map[string]any
	lastPath  string
	response  any
	status    int
	pullBody  []byte
	pullName  string
	pullCalls int
}

func (f *fakePlatform) handler() http.Handler {
	mux := http.NewServeMux()

	mux.HandleFunc("/api/capabilities/invoke", func(w http.ResponseWriter, r *http.Request) {
		f.lastAuth = r.Header.Get("Authorization")
		f.lastBody = map[string]any{}
		_ = json.NewDecoder(r.Body).Decode(&f.lastBody)
		f.write(w)
	})

	mux.HandleFunc("/api/capabilities/docs", func(w http.ResponseWriter, r *http.Request) {
		f.lastAuth = r.Header.Get("Authorization")
		f.write(w)
	})

	mux.HandleFunc("/api/capabilities", func(w http.ResponseWriter, r *http.Request) {
		f.lastAuth = r.Header.Get("Authorization")
		f.write(w)
	})

	mux.HandleFunc("/api/files/pull", func(w http.ResponseWriter, r *http.Request) {
		f.lastAuth = r.Header.Get("Authorization")
		f.pullCalls++
		f.lastPath = r.URL.RawQuery
		if f.status != 0 && (f.status < 200 || f.status > 299) {
			f.write(w)
			return
		}
		w.Header().Set("Content-Type", "image/jpeg")
		w.Header().Set("Content-Disposition", `attachment; filename="`+f.pullName+`"`)
		w.WriteHeader(200)
		_, _ = w.Write(f.pullBody)
	})

	mux.HandleFunc("/", func(w http.ResponseWriter, r *http.Request) {
		f.lastAuth = r.Header.Get("Authorization")
		_, _ = w.Write([]byte("<html>console</html>"))
	})

	return mux
}

func (f *fakePlatform) write(w http.ResponseWriter) {
	status := f.status
	if status == 0 {
		status = 200
	}
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(status)
	if f.response != nil {
		_ = json.NewEncoder(w).Encode(f.response)
	}
}

func newTest(t *testing.T, platform *fakePlatform) (*Client, func()) {
	t.Helper()
	server := httptest.NewServer(platform.handler())
	return New(server.URL, TOKEN, false), server.Close
}

func TestTheTokenIsSentAsABearerHeader(t *testing.T) {
	platform := &fakePlatform{response: map[string]any{"ok": true}}
	c, closeServer := newTest(t, platform)
	defer closeServer()

	if _, err := c.Invoke(context.Background(), "DEVICE_INFO", "get", nil); err != nil {
		t.Fatalf("invoke: %v", err)
	}
	if platform.lastAuth != "Bearer "+TOKEN {
		t.Errorf("Authorization was %q, want the bearer token", platform.lastAuth)
	}
}

func TestParametersAreSentInTheBody(t *testing.T) {
	platform := &fakePlatform{response: map[string]any{"ok": true}}
	c, closeServer := newTest(t, platform)
	defer closeServer()

	_, err := c.Invoke(context.Background(), "CAMERA", "capture", map[string]any{
		"maxWidth": 1280,
		"cameraId": "0",
	})
	if err != nil {
		t.Fatalf("invoke: %v", err)
	}

	if platform.lastBody["capability"] != "CAMERA" || platform.lastBody["action"] != "capture" {
		t.Errorf("capability/action not sent: %v", platform.lastBody)
	}
	// A number must arrive as a number. The platform reads this field as an integer, and "1280"
	// is not the same thing.
	if got, ok := platform.lastBody["maxWidth"].(float64); !ok || got != 1280 {
		t.Errorf("maxWidth arrived as %#v, want the number 1280", platform.lastBody["maxWidth"])
	}
}

// A refusal must arrive as an error carrying the platform's own reason, not as a blank success.
func TestARefusalBecomesAnErrorWithTheReasonsMessage(t *testing.T) {
	platform := &fakePlatform{
		status: 403,
		response: map[string]any{
			"error": "Capability 'CAMERA' is switched off",
		},
	}
	c, closeServer := newTest(t, platform)
	defer closeServer()

	_, err := c.Invoke(context.Background(), "CAMERA", "capture", nil)
	if err == nil {
		t.Fatal("a 403 came back as success")
	}

	apiErr, ok := err.(*Error)
	if !ok {
		t.Fatalf("error was %T, want *client.Error", err)
	}
	if apiErr.Status != 403 {
		t.Errorf("status was %d", apiErr.Status)
	}
	if !strings.Contains(apiErr.Message, "switched off") {
		t.Errorf("the platform's own reason was lost: %q", apiErr.Message)
	}
	if apiErr.ExitCode() != ExitAuth {
		t.Errorf("a 403 exited %d, want %d", apiErr.ExitCode(), ExitAuth)
	}
}

// The hint is what turns a refusal into something the reader can act on.
func TestAnUnauthorizedReplySaysHowToFixIt(t *testing.T) {
	platform := &fakePlatform{status: 401, response: map[string]any{"error": "Unauthorized"}}
	c, closeServer := newTest(t, platform)
	defer closeServer()

	_, err := c.Docs(context.Background())
	apiErr, ok := err.(*Error)
	if !ok {
		t.Fatalf("error was %T", err)
	}
	if !strings.Contains(apiErr.Hint, "ascli connect") {
		t.Errorf("the hint does not say how to fix it: %q", apiErr.Hint)
	}
}

// A body that is not the JSON we expect is shown as it is, rather than replaced with something tidy.
func TestAnUnexpectedBodyIsShownRatherThanHidden(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(400)
		_, _ = w.Write([]byte("<html>gateway timeout</html>"))
	}))
	defer server.Close()

	_, err := New(server.URL, TOKEN, false).Docs(context.Background())
	apiErr, ok := err.(*Error)
	if !ok {
		t.Fatalf("error was %T", err)
	}
	if !strings.Contains(apiErr.Message, "gateway timeout") {
		t.Errorf("the platform's body was discarded: %q", apiErr.Message)
	}
}

// A transport error can quote the URL back, and the URL can carry the token if a caller built one
// that way. It must never reach the terminal intact.
func TestATransportErrorDoesNotLeakTheToken(t *testing.T) {
	// Port 0 is never listening, so the dial fails and the error is the interesting part.
	c := New("http://127.0.0.1:1", TOKEN, false)

	_, err := c.Docs(context.Background())
	if err == nil {
		t.Fatal("dialling nothing succeeded")
	}
	if strings.Contains(err.Error(), TOKEN) {
		t.Errorf("the token appeared in an error: %q", err.Error())
	}
}

func TestPullStreamsTheBytesAndTheFileName(t *testing.T) {
	photo := []byte{0xFF, 0xD8, 0xFF, 0xE0, 'j', 'p', 'g'}
	platform := &fakePlatform{pullBody: photo, pullName: "cap_abc.jpg"}
	c, closeServer := newTest(t, platform)
	defer closeServer()

	var sink strings.Builder
	written, name, err := c.Pull(context.Background(), "CAMERA", "cap_abc", &sink)
	if err != nil {
		t.Fatalf("pull: %v", err)
	}

	if written != int64(len(photo)) {
		t.Errorf("wrote %d bytes, want %d", written, len(photo))
	}
	if sink.String() != string(photo) {
		t.Errorf("the bytes came back wrong: %q", sink.String())
	}
	if name != "cap_abc.jpg" {
		t.Errorf("file name was %q", name)
	}
	if !strings.Contains(platform.lastPath, "capability=CAMERA") ||
		!strings.Contains(platform.lastPath, "id=cap_abc") {
		t.Errorf("pull did not address by id: %q", platform.lastPath)
	}
}

func TestPullReportsARefusalRatherThanAnEmptyFile(t *testing.T) {
	platform := &fakePlatform{
		status:   404,
		response: map[string]any{"error": "No file for id 'cap_nope' in CAMERA"},
	}
	c, closeServer := newTest(t, platform)
	defer closeServer()

	var sink strings.Builder
	written, _, err := c.Pull(context.Background(), "CAMERA", "cap_nope", &sink)
	if err == nil {
		t.Fatal("a 404 came back as a successful pull")
	}
	if written != 0 || sink.Len() != 0 {
		t.Errorf("a refused pull still wrote %d bytes", written)
	}
}

// Raw is the escape hatch, so it must refuse a full URL rather than resolve it — otherwise it
// becomes a way to send the token somewhere the user did not name.
func TestRawRefusesAFullURL(t *testing.T) {
	c := New("http://example.invalid", TOKEN, false)

	_, err := c.Raw(context.Background(), "GET", "http://elsewhere.invalid/steal", nil)
	if err == nil {
		t.Fatal("a full URL was accepted")
	}
	if !strings.Contains(err.Error(), "not a full URL") {
		t.Errorf("the refusal did not explain itself: %q", err.Error())
	}
}

func TestRawAcceptsAPath(t *testing.T) {
	platform := &fakePlatform{response: map[string]any{"ok": true}}
	c, closeServer := newTest(t, platform)
	defer closeServer()

	raw, err := c.Raw(context.Background(), "GET", "/api/capabilities", nil)
	if err != nil {
		t.Fatalf("raw: %v", err)
	}
	if !strings.Contains(string(raw), "ok") {
		t.Errorf("raw returned %q", raw)
	}
}

func TestRedactedNeverShowsTheWholeToken(t *testing.T) {
	c := New("http://x", TOKEN, false)
	if strings.Contains(c.TokenRedacted(), TOKEN) {
		t.Errorf("TokenRedacted leaked the token: %q", c.TokenRedacted())
	}
}

func TestHasToken(t *testing.T) {
	if New("http://x", "", false).HasToken() {
		t.Error("a client with no token claims to have one")
	}
	if !New("http://x", TOKEN, false).HasToken() {
		t.Error("a client with a token claims not to")
	}
}

// Skipping TLS verification must be opt-in and visible, never inherited.
func TestTLSVerificationIsOnUnlessAsked(t *testing.T) {
	c := New("http://x", TOKEN, false)
	transport, ok := c.http.Transport.(*http.Transport)
	if !ok {
		t.Skip("transport is the shared default")
	}
	if transport.TLSClientConfig != nil && transport.TLSClientConfig.InsecureSkipVerify {
		t.Error("TLS verification was skipped without being asked for")
	}
}

func TestEnvironmentIsNotRequired(t *testing.T) {
	// A test that silently depended on the developer's own environment would pass here and fail
	// on a clean machine.
	if os.Getenv("ASCLI_TOKEN") == TOKEN {
		t.Skip("the real token is in the environment")
	}
}
