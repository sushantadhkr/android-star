package main

import (
	"bytes"
	"encoding/json"
	"os"
	"strings"
	"testing"

	"github.com/sushantadhkr/android-star/ascli/internal/client"
	"github.com/sushantadhkr/android-star/ascli/internal/cmds"
)

// run executes ascli end to end and returns its stdout, stderr and exit code.
//
// Driven through main rather than through the internal functions, so a test can catch the thing
// unit tests miss: whether the argument parsing, the flag handling and the exit code actually line
// up with each other.
type result struct {
	stdout string
	stderr string
	code   int
}

func ascli(t *testing.T, args ...string) result {
	t.Helper()
	var out, errOut bytes.Buffer
	oldOut, oldErr := os.Stdout, os.Stderr
	r, w, err := os.Pipe()
	if err != nil {
		t.Fatalf("pipe: %v", err)
	}
	os.Stdout, os.Stderr = w, w

	done := make(chan struct{})
	var captured bytes.Buffer
	go func() {
		buf := make([]byte, 4096)
		for {
			n, err := r.Read(buf)
			if n > 0 {
				captured.Write(buf[:n])
			}
			if err != nil {
				close(done)
				return
			}
		}
	}()

	code := run(args)

	w.Close()
	os.Stdout, os.Stderr = oldOut, oldErr
	<-done
	combined := captured.String()
	_ = out
	_ = errOut

	return result{stdout: combined, stderr: combined, code: code}
}

// The tests below all run without a platform, so they use the bundled docs fixture. Anything that
// needs a phone is skipped rather than faked.

func TestHelpListsTheShapeOfTheTool(t *testing.T) {
	got := ascli(t, "--help")
	if got.code != client.ExitOK {
		t.Fatalf("--help exited %d", got.code)
	}
	for _, want := range []string{"ascli connect", "ascli doctor", "ascli platform", "ascli pull", "ascli invoke"} {
		if !strings.Contains(got.stdout, want) {
			t.Errorf("help does not mention %q", want)
		}
	}
}

func TestVersionPrintsSomething(t *testing.T) {
	got := ascli(t, "--version")
	if got.code != client.ExitOK || !strings.Contains(got.stdout, "ascli") {
		t.Errorf("--version gave %q with code %d", got.stdout, got.code)
	}
}

func TestNoArgumentsIsAUsageError(t *testing.T) {
	got := ascli(t)
	if got.code != client.ExitUsage {
		t.Errorf("no arguments exited %d, want %d", got.code, client.ExitUsage)
	}
}

// Every action the platform documents must be reachable by its own name. This is the promise that
// makes the CLI comprehensive rather than a selection of the easy ones.
func TestEveryActionIsReachable(t *testing.T) {
	docs, err := cmds.LoadFixture()
	if err != nil {
		t.Skipf("no fixture: %v", err)
	}
	for _, capability := range docs.Capabilities {
		for _, action := range capability.Actions {
			path := cmds.CommandName(capability.ID) + " " + cmds.CommandName(action.Name)
			if _, found := cmds.FindCommand(path); !found {
				t.Errorf("no command for %s (%s.%s)", path, capability.ID, action.Name)
			}
		}
	}
}

// A feature name on its own should say what it is, not be mistaken for an action.
func TestABareFeatureNameIsExplained(t *testing.T) {
	got := ascli(t, "device-info")
	if got.code == client.ExitOK {
		t.Errorf("a bare feature name exited 0")
	}
	if !strings.Contains(got.stdout, "Platform Feature") {
		t.Errorf("a bare feature name did not explain itself: %q", got.stdout)
	}
}

func TestAnUnknownCommandSaysWhatToDoNext(t *testing.T) {
	got := ascli(t, "definitely-not-a-command")
	if got.code != client.ExitNotFound {
		t.Errorf("unknown command exited %d, want %d", got.code, client.ExitNotFound)
	}
	if !strings.Contains(got.stdout, "capabilities") {
		t.Errorf("unknown command gave no way forward: %q", got.stdout)
	}
}

// A missing required parameter must be caught here, where the help can be shown, rather than sent
// to the platform to be refused.
func TestAMissingRequiredParameterIsExplained(t *testing.T) {
	got := ascli(t, "camera", "pull", "--json")
	if got.code == client.ExitOK {
		t.Fatalf("a missing required parameter exited 0")
	}
	if !strings.Contains(got.stdout, "needs --id") {
		t.Errorf("missing parameter was not named: %q", got.stdout)
	}
}

func TestCompletionScriptsAreProducedForEveryShell(t *testing.T) {
	for _, shell := range []string{"bash", "zsh", "fish", "powershell"} {
		got := ascli(t, "completion", shell)
		if got.code != client.ExitOK {
			t.Errorf("completion for %s exited %d", shell, got.code)
			continue
		}
		if len(got.stdout) < 100 {
			t.Errorf("completion for %s is suspiciously short: %q", shell, got.stdout)
		}
	}
}

func TestCompletionForAnUnknownShellIsRefused(t *testing.T) {
	got := ascli(t, "completion", "csh")
	if got.code == client.ExitOK {
		t.Errorf("completion for an unknown shell exited 0")
	}
}

// Completion must offer the commands the platform actually has, or it teaches the wrong thing.
//
// Each shell completes in its own way — bash offers a feature and then its actions, so the check
// is that both halves are present rather than that one joined string appears.
func TestCompletionOffersRealCommands(t *testing.T) {
	bash := ascli(t, "completion", "bash")
	for _, want := range []string{`"camera capture"`, `"download-manager fetch"`, `"device-info") actions="get"`} {
		if !strings.Contains(bash.stdout, want) {
			t.Errorf("bash completion does not offer %q", want)
		}
	}

	for _, shell := range []string{"zsh", "fish", "powershell"} {
		got := ascli(t, "completion", shell)
		for _, want := range []string{"camera", "device-info"} {
			if !strings.Contains(got.stdout, want) {
				t.Errorf("%s completion does not offer %q", shell, want)
			}
		}
	}
}

func TestCapabilitiesWorksWithNoPlatformConnected(t *testing.T) {
	// Reading the API's shape is the first thing anyone does, so it has to work before connecting.
	got := ascli(t, "capabilities", "--json")
	if got.code != client.ExitOK {
		t.Fatalf("capabilities with no platform exited %d: %s", got.code, got.stdout)
	}
	var payload struct {
		Capabilities []struct {
			ID      string `json:"id"`
			Actions []struct {
				Name string `json:"name"`
			} `json:"actions"`
		} `json:"capabilities"`
	}
	if err := json.Unmarshal([]byte(got.stdout), &payload); err != nil {
		t.Fatalf("capabilities did not print JSON: %v\n%s", err, got.stdout)
	}
	if len(payload.Capabilities) == 0 {
		t.Fatal("capabilities listed nothing")
	}
}

func TestCapabilityShowsItsActions(t *testing.T) {
	got := ascli(t, "capability", "CAMERA", "--no-json")
	if got.code != client.ExitOK {
		t.Fatalf("capability CAMERA exited %d: %s", got.code, got.stdout)
	}
	for _, want := range []string{"ascli camera capture", "ascli camera pull", "--cameraId"} {
		if !strings.Contains(got.stdout, want) {
			t.Errorf("capability CAMERA does not show %q", want)
		}
	}
}

func TestAnUnknownFeatureIsA404WithAWayForward(t *testing.T) {
	got := ascli(t, "capability", "NOT_REAL")
	if got.code != client.ExitNotFound {
		t.Errorf("unknown feature exited %d, want %d", got.code, client.ExitNotFound)
	}
	if !strings.Contains(got.stdout, "capabilities") {
		t.Errorf("unknown feature gave no way forward: %q", got.stdout)
	}
}

func TestDoctorWithNoPlatformExplainsItself(t *testing.T) {
	t.Setenv("ASCLI_CONFIG", t.TempDir()+"/none.json")
	got := ascli(t, "doctor")
	if got.code == client.ExitOK {
		t.Errorf("doctor with nothing configured exited 0")
	}
	if !strings.Contains(got.stdout, "platform") {
		t.Errorf("doctor did not mention what is missing: %q", got.stdout)
	}
}

func TestCommandsNeedAPlatformToRun(t *testing.T) {
	// Nothing configured means nothing to talk to, and that must be an error rather than an
	// empty success.
	got := ascli(t, "device-info", "get", "--json")
	if got.code == client.ExitOK {
		t.Errorf("an action with no platform exited 0")
	}
}
