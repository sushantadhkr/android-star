package cmds

import (
	"context"
	"encoding/json"
	"io"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"

	"github.com/sushantadhkr/android-star/ascli/internal/client"
)

// recorder is a stand-in platform that remembers what it was sent, so the tests can assert on the
// bytes rather than on the CLI's own idea of them.
type recorder struct {
	body   map[string]any
	path   string
	status int
}

func (r *recorder) server(t *testing.T) *httptest.Server {
	t.Helper()
	mux := http.NewServeMux()
	mux.HandleFunc("/api/capabilities/invoke", func(w http.ResponseWriter, req *http.Request) {
		r.path = req.URL.Path
		r.body = map[string]any{}
		_ = json.NewDecoder(req.Body).Decode(&r.body)

		if r.status != 0 {
			w.Header().Set("Content-Type", "application/json")
			w.WriteHeader(r.status)
			_, _ = w.Write([]byte(`{"error":"stubbed refusal"}`))
			return
		}
		w.Header().Set("Content-Type", "application/json")
		_, _ = w.Write([]byte(`{"ok":true,"id":"stub_1"}`))
	})
	server := httptest.NewServer(mux)
	t.Cleanup(server.Close)
	return server
}

func newClient(t *testing.T, rec *recorder) *client.Client {
	t.Helper()
	return client.New(rec.server(t).URL, "token", false)
}

func quietOutput() *Output {
	return &Output{JSON: true, Out: io.Discard, Err: io.Discard}
}

var captureCommand = GeneratedCommand{
	Capability: "CAMERA",
	Action:     "capture",
	Path:       "camera capture",
	Params: []client.DocParam{
		{Name: "cameraId", Type: "string", Required: false, Description: "Which camera"},
		{Name: "maxWidth", Type: "integer", Required: false, Description: "Cap the width", Example: "1920"},
	},
}

// The platform spells parameters maxWidth; a shell reads max-width better. Both must work,
// because the documentation shows one and people type the other.
func TestBothParameterSpellingsAreAccepted(t *testing.T) {
	for _, flag := range []string{"maxWidth", "max-width", "MAXWIDTH", "max_width"} {
		rec := &recorder{}
		c := newClient(t, rec)

		_, err := InvokeGenerated(context.Background(), c, captureCommand,
			map[string]string{flag: "1280"})
		if err != nil {
			t.Fatalf("--%s was rejected: %v", flag, err)
		}
		if got, ok := rec.body["maxWidth"].(float64); !ok || got != 1280 {
			t.Errorf("--%s was sent as %#v, want the number 1280 under 'maxWidth'",
				flag, rec.body["maxWidth"])
		}
	}
}

// A number must reach the platform as a number. It reads the field as an integer, and "1280" is a
// different thing entirely.
func TestNumbersAreSentAsNumbers(t *testing.T) {
	rec := &recorder{}
	c := newClient(t, rec)

	_, err := InvokeGenerated(context.Background(), c, captureCommand,
		map[string]string{"max-width": "1280"})
	if err != nil {
		t.Fatalf("invoke: %v", err)
	}
	value, ok := rec.body["maxWidth"].(float64)
	if !ok {
		t.Fatalf("maxWidth arrived as %T, want a number", rec.body["maxWidth"])
	}
	if value != 1280 {
		t.Errorf("maxWidth was %v, want 1280", value)
	}
}

func TestANonNumericValueForANumberIsRefusedHere(t *testing.T) {
	rec := &recorder{}
	c := newClient(t, rec)

	_, err := InvokeGenerated(context.Background(), c, captureCommand,
		map[string]string{"maxWidth": "wide"})
	if err == nil {
		t.Fatal("a non-numeric value was accepted for a number")
	}
	if !strings.Contains(err.Error(), "whole number") {
		t.Errorf("the refusal did not explain itself: %q", err.Error())
	}
	// Nothing should have been sent.
	if rec.body != nil {
		t.Errorf("a request went out despite the bad value: %v", rec.body)
	}
}

func TestBooleansAreSentAsBooleans(t *testing.T) {
	cmd := GeneratedCommand{
		Capability: "INSTALLED_APPS",
		Action:     "list",
		Path:       "installed-apps list",
		Params: []client.DocParam{
			{Name: "includeSystem", Type: "boolean", Required: false, Description: "Include system apps"},
		},
	}

	rec := &recorder{}
	c := newClient(t, rec)
	if _, err := InvokeGenerated(context.Background(), c, cmd,
		map[string]string{"include-system": "true"}); err != nil {
		t.Fatalf("invoke: %v", err)
	}
	if got, ok := rec.body["includeSystem"].(bool); !ok || !got {
		t.Errorf("includeSystem arrived as %#v, want true", rec.body["includeSystem"])
	}
}

// A bare flag means true, which is what makes --include-system work without a value.
func TestABareFlagMeansTrue(t *testing.T) {
	cmd := GeneratedCommand{
		Capability: "INSTALLED_APPS",
		Action:     "list",
		Path:       "installed-apps list",
		Params: []client.DocParam{
			{Name: "includeSystem", Type: "boolean", Required: false, Description: "Include system apps"},
		},
	}

	rec := &recorder{}
	c := newClient(t, rec)
	if _, err := InvokeGenerated(context.Background(), c, cmd,
		map[string]string{"include-system": "true"}); err != nil {
		t.Fatalf("invoke: %v", err)
	}
	if rec.body["includeSystem"] != true {
		t.Errorf("includeSystem was %#v", rec.body["includeSystem"])
	}
}

func TestARequiredParameterIsCheckedBeforeSending(t *testing.T) {
	cmd := GeneratedCommand{
		Capability: "CAMERA",
		Action:     "pull",
		Path:       "camera pull",
		Params: []client.DocParam{
			{Name: "id", Type: "string", Required: true, Description: "The capture id", Example: "cap_1"},
		},
	}

	rec := &recorder{}
	c := newClient(t, rec)

	_, err := InvokeGenerated(context.Background(), c, cmd, nil)
	if err == nil {
		t.Fatal("a missing required parameter was accepted")
	}
	if !strings.Contains(err.Error(), "needs --id") {
		t.Errorf("the required parameter was not named: %q", err.Error())
	}
	if rec.body != nil {
		t.Errorf("a request went out anyway: %v", rec.body)
	}
}

// Client-side flags are not platform parameters and must not be forwarded as though they were.
func TestClientSideFlagsAreNotForwarded(t *testing.T) {
	rec := &recorder{}
	c := newClient(t, rec)

	_, err := InvokeGenerated(context.Background(), c, captureCommand, map[string]string{
		"watch": "true",
	})
	if err != nil {
		t.Fatalf("invoke: %v", err)
	}
	if _, present := rec.body["watch"]; present {
		t.Errorf("a client-side flag reached the platform: %v", rec.body)
	}
}

// An action this build has never heard of must still be reachable, or an out-of-date CLI breaks.
func TestAnUnknownParameterIsPassedThroughRatherThanRefused(t *testing.T) {
	rec := &recorder{}
	c := newClient(t, rec)

	// A capability this binary was not generated with.
	cmd := GeneratedCommand{
		Capability: "SOMETHING_NEW",
		Action:     "doThing",
		Path:       "something-new do-thing",
	}

	_, err := InvokeGenerated(context.Background(), c, cmd, map[string]string{"whatever": "x"})
	if err != nil {
		t.Fatalf("an unknown parameter was refused: %v", err)
	}
	if rec.body["whatever"] != "x" {
		t.Errorf("the parameter did not reach the platform: %v", rec.body)
	}
	if rec.body["capability"] != "SOMETHING_NEW" || rec.body["action"] != "doThing" {
		t.Errorf("the wrong action was sent: %v", rec.body)
	}
}

// IDFrom is what makes `-o` work: it reads the id out of whatever the action said.
func TestIDIsReadFromTheReply(t *testing.T) {
	for _, reply := range []string{
		`{"id":"cap_1","bytes":10}`,
		`{"downloadId":"dl_9","status":"queued"}`,
		`{"captureId":"cap_2"}`,
	} {
		got, err := IDFrom([]byte(reply))
		if err != nil {
			t.Fatalf("%s: %v", reply, err)
		}
		if got == "" {
			t.Errorf("%s yielded no id", reply)
		}
	}
}

func TestNoIDIsSaidRatherThanFetchedSilently(t *testing.T) {
	_, err := IDFrom([]byte(`{"speaking":true}`))
	if err == nil {
		t.Fatal("a reply with no id was accepted")
	}
	if !strings.Contains(err.Error(), "did not report an id") {
		t.Errorf("the refusal did not explain itself: %q", err.Error())
	}
}

// A refusal must reach the caller with the platform's own status, not be swallowed.
func TestARefusalPropagates(t *testing.T) {
	rec := &recorder{status: 403}
	c := newClient(t, rec)

	_, err := InvokeGenerated(context.Background(), c, captureCommand, nil)
	if err == nil {
		t.Fatal("a 403 came back as success")
	}
	apiErr, ok := err.(*client.Error)
	if !ok {
		t.Fatalf("error was %T", err)
	}
	if apiErr.Status != 403 {
		t.Errorf("status was %d, want 403", apiErr.Status)
	}
}

// The typed fallback must reach the same place with the same typing as the generated path, or a
// --watch would poll with different values than the first call sent.
func TestTheTypedFallbackNormalisesUnknownNames(t *testing.T) {
	rec := &recorder{}
	c := newClient(t, rec)

	if _, err := invokeTyped(context.Background(), c, "CAMERA", "capture",
		map[string]string{"max-width": "1280"}); err != nil {
		t.Fatalf("invokeTyped: %v", err)
	}
	// No docs reachable here, so the fallback cannot know the type — but it must still put the
	// parameter under the platform's own name, or it would be refused for a reason the user cannot
	// act on.
	if rec.body["maxWidth"] != "1280" {
		t.Errorf("the fallback sent %#v, want the value under 'maxWidth'", rec.body)
	}
	if _, wrong := rec.body["max-width"]; wrong {
		t.Errorf("the fallback sent the dashed name: %v", rec.body)
	}
}
