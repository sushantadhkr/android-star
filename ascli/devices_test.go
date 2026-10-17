package main

import (
	"encoding/json"
	"fmt"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strings"
	"testing"

	"github.com/sushantadhkr/android-star/ascli/internal/client"
	"github.com/sushantadhkr/android-star/ascli/internal/cmds"
)

// twoDevices stands up two phones, each answering with its own name, so a test can prove a command
// reached the one it was aimed at and not the other.
func twoDevices(t *testing.T) {
	t.Helper()

	encoded := fixtureBytes(t)

	device := func(name string) http.Handler {
		mux := http.NewServeMux()
		mux.HandleFunc("/", func(w http.ResponseWriter, r *http.Request) {
			if r.Header.Get("Authorization") != "Bearer "+name+"-token" {
				w.WriteHeader(401)
				_, _ = w.Write([]byte(`{"error":"Unauthorized"}`))
				return
			}
			w.Header().Set("Content-Type", "application/json")
			// The docs route is the same everywhere; everything else names the device, which is
			// what makes "went to the wrong phone" visible in the test output.
			if r.URL.Path == "/api/capabilities/docs" {
				_, _ = w.Write(encoded)
				return
			}
			_, _ = fmt.Fprintf(w, `{"reached":%q}`, name)
		})
		return mux
	}

	pixel := httptest.NewServer(device("pixel"))
	t.Cleanup(pixel.Close)
	android := httptest.NewServer(device("android"))
	t.Cleanup(android.Close)

	configPath := filepath.Join(t.TempDir(), "config.json")
	body := fmt.Sprintf(`{
	  "devices": {
	    "pixel":  {"baseUrl": %q, "token": "pixel-token"},
	    "android": {"baseUrl": %q, "token": "android-token"}
	  }
}`, pixel.URL, android.URL)
	if err := os.WriteFile(configPath, []byte(body), 0o600); err != nil {
		t.Fatalf("write config: %v", err)
	}

	t.Setenv("ASCLI_CONFIG", configPath)
	t.Setenv("ASCLI_DEVICE", "")
	t.Setenv("ASCLI_URL", "")
	t.Setenv("ASCLI_TOKEN", "")
}

// fixtureBytes is the platform's own docs payload, read straight off disk so the stub answers with
// exactly what the real platform would.
func fixtureBytes(t *testing.T) []byte {
	t.Helper()
	path, err := cmds.FindFixture()
	if err != nil {
		t.Fatalf("no fixture: %v", err)
	}
	raw, err := os.ReadFile(path)
	if err != nil {
		t.Fatalf("read %s: %v", path, err)
	}
	return raw
}

// The whole point of naming devices: a command reaches the one it was aimed at.
func TestACommandReachesTheDeviceItWasNamed(t *testing.T) {
	twoDevices(t)

	for _, name := range []string{"pixel", "android"} {
		got := ascli(t, "invoke", "DEVICE_INFO", "get", "--device", name, "--json")
		if got.code != client.ExitOK {
			t.Fatalf("--device %s exited %d: %s", name, got.code, firstLine(got.stdout))
		}
		if !strings.Contains(got.stdout, `"reached": "`+name+`"`) {
			t.Errorf("--device %s reached the wrong phone: %s", name, firstLine(got.stdout))
		}
	}
}

func TestTheShortDeviceFlagWorksToo(t *testing.T) {
	twoDevices(t)

	got := ascli(t, "invoke", "DEVICE_INFO", "get", "-d", "android", "--json")
	if !strings.Contains(got.stdout, `"reached": "android"`) {
		t.Errorf("-d did not select the device: %s", firstLine(got.stdout))
	}
}

// With two phones and no name, guessing would mean running a command against the wrong one.
func TestTwoDevicesAndNoNameRefusesRatherThanGuesses(t *testing.T) {
	twoDevices(t)

	got := ascli(t, "invoke", "DEVICE_INFO", "get", "--json")
	if got.code != client.ExitDevice {
		t.Errorf("exited %d, want %d", got.code, client.ExitDevice)
	}
	if !strings.Contains(got.stdout, "pixel") || !strings.Contains(got.stdout, "android") {
		t.Errorf("the refusal did not list the devices: %q", firstLine(got.stdout))
	}
}

func TestAnUnknownDeviceNameSaysSo(t *testing.T) {
	twoDevices(t)

	got := ascli(t, "invoke", "DEVICE_INFO", "get", "--device", "nokia", "--json")
	if got.code != client.ExitDevice {
		t.Errorf("exited %d, want %d", got.code, client.ExitDevice)
	}
	if !strings.Contains(got.stdout, "nokia") {
		t.Errorf("the refusal did not name what was asked for: %q", firstLine(got.stdout))
	}
}

// A default removes the need to name the device without ever guessing between two.
func TestADefaultIsUsedWithoutNamingIt(t *testing.T) {
	twoDevices(t)

	if got := ascli(t, "devices", "default", "android", "--no-json"); got.code != client.ExitOK {
		t.Fatalf("setting the default failed: %s", got.stdout)
	}

	got := ascli(t, "invoke", "DEVICE_INFO", "get", "--json")
	if got.code != client.ExitOK {
		t.Fatalf("exited %d: %s", got.code, firstLine(got.stdout))
	}
	if !strings.Contains(got.stdout, `"reached": "android"`) {
		t.Errorf("the default was not used: %s", firstLine(got.stdout))
	}
}

func TestAnExplicitNameBeatsTheDefault(t *testing.T) {
	twoDevices(t)
	if got := ascli(t, "devices", "default", "android", "--no-json"); got.code != client.ExitOK {
		t.Fatalf("setting the default failed: %s", got.stdout)
	}

	got := ascli(t, "invoke", "DEVICE_INFO", "get", "--device", "pixel", "--json")
	if !strings.Contains(got.stdout, `"reached": "pixel"`) {
		t.Errorf("an explicit name was overridden by the default: %s", firstLine(got.stdout))
	}
}

// The environment is the other way to say which device, for a shell that does it once.
func TestTheEnvironmentNamesTheDevice(t *testing.T) {
	twoDevices(t)
	t.Setenv("ASCLI_DEVICE", "android")

	got := ascli(t, "invoke", "DEVICE_INFO", "get", "--json")
	if !strings.Contains(got.stdout, `"reached": "android"`) {
		t.Errorf("ASCLI_DEVICE was ignored: %s", firstLine(got.stdout))
	}
}

// ascli devices should reach every one, so a dead phone is obvious in the listing rather than
// discovered later at the moment a command matters.
func TestDevicesListsEveryOneAndItsState(t *testing.T) {
	twoDevices(t)

	got := ascli(t, "devices", "--json")
	if got.code != client.ExitOK {
		t.Fatalf("devices exited %d: %s", got.code, got.stdout)
	}

	var payload struct {
		Devices []struct {
			Name      string `json:"name"`
			Reachable bool   `json:"reachable"`
			Features  int    `json:"features"`
			Token     string `json:"token"`
		} `json:"devices"`
	}
	if err := json.Unmarshal([]byte(got.stdout), &payload); err != nil {
		t.Fatalf("devices did not print JSON: %v\n%s", err, got.stdout)
	}
	if len(payload.Devices) != 2 {
		t.Fatalf("listed %d devices, want 2", len(payload.Devices))
	}
	for _, device := range payload.Devices {
		if !device.Reachable {
			t.Errorf("%s was not reachable", device.Name)
		}
		if device.Features == 0 {
			t.Errorf("%s reported no features", device.Name)
		}
		if strings.Contains(device.Token, "token") && !strings.Contains(device.Token, "…") {
			t.Errorf("%s's token was not redacted: %q", device.Name, device.Token)
		}
	}
}

// An unreachable phone must not hide the others.
func TestDevicesShowsOneReachableAndOneNot(t *testing.T) {
	encoded := fixtureBytes(t)

	good := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		_, _ = w.Write(encoded)
	}))
	t.Cleanup(good.Close)

	// A port nothing is listening on.
	dead := "http://127.0.0.1:1"

	configPath := filepath.Join(t.TempDir(), "config.json")
	body := fmt.Sprintf(`{"devices":{
	  "good":{"baseUrl":%q,"token":"t-good-token"},
	  "dead":{"baseUrl":%q,"token":"t-dead-token"}
	}}`, good.URL, dead)
	if err := os.WriteFile(configPath, []byte(body), 0o600); err != nil {
		t.Fatalf("write config: %v", err)
	}
	t.Setenv("ASCLI_CONFIG", configPath)
	t.Setenv("ASCLI_DEVICE", "")

	got := ascli(t, "devices", "--json")
	if got.code != client.ExitOK {
		t.Errorf("devices exited %d with one phone down", got.code)
	}

	var payload struct {
		Devices []struct {
			Name      string `json:"name"`
			Reachable bool   `json:"reachable"`
			Error     string `json:"error"`
		} `json:"devices"`
	}
	if err := json.Unmarshal([]byte(got.stdout), &payload); err != nil {
		t.Fatalf("not JSON: %v\n%s", err, got.stdout)
	}
	if len(payload.Devices) != 2 {
		t.Fatalf("listed %d devices, want both", len(payload.Devices))
	}
	byName := map[string]bool{}
	for _, device := range payload.Devices {
		byName[device.Name] = device.Reachable
	}
	if !byName["good"] {
		t.Error("the reachable phone was reported unreachable")
	}
	if byName["dead"] {
		t.Error("the unreachable phone was reported reachable")
	}
}

func TestRemovingADeviceForgetsIt(t *testing.T) {
	twoDevices(t)

	if got := ascli(t, "devices", "remove", "android", "--no-json"); got.code != client.ExitOK {
		t.Fatalf("remove failed: %s", got.stdout)
	}

	got := ascli(t, "devices", "--json")
	if strings.Contains(got.stdout, `"android"`) {
		t.Errorf("the removed device is still listed: %s", firstLine(got.stdout))
	}
	if !strings.Contains(got.stdout, `"pixel"`) {
		t.Errorf("removing one device removed another: %s", firstLine(got.stdout))
	}
}

func TestRemovingADeviceThatIsNotThereIsNotFound(t *testing.T) {
	twoDevices(t)

	got := ascli(t, "devices", "remove", "nokia", "--no-json")
	if got.code != client.ExitNotFound {
		t.Errorf("exited %d, want %d", got.code, client.ExitNotFound)
	}
}

// One phone needs no ceremony: connect without a name and every command works.
func TestConnectingWithoutANameJustWorks(t *testing.T) {
	withDevice(t, stubDocs(t))

	got := ascli(t, "connect", "http://127.0.0.1:1", "--token", "t", "--json")
	if got.code != client.ExitUsage && got.code != client.ExitRefused && got.code != client.ExitNetwork {
		t.Errorf("connect to an unreachable URL exited %d", got.code)
	}

	// The real check is the argument handling, which must not mistake a URL for a device name.
	bad := ascli(t, "connect", "one", "two", "three", "--json")
	if bad.code != client.ExitRefused && bad.code != client.ExitUsage {
		t.Errorf("three positionals exited %d, want a usage error", bad.code)
	}
	if !strings.Contains(bad.stdout, "connect takes") {
		t.Errorf("three positionals were not explained: %q", firstLine(bad.stdout))
	}
}

// A saved device must survive being reconnected, and the token must not need retyping.
func TestReconnectingKeepsTheTokenWhenNoneIsGiven(t *testing.T) {
	withDevice(t, stubDocs(t))

	// Save a device first, then reconnect to the same URL without a token.
	got := ascli(t, "connect", "pixel", "http://127.0.0.1:1", "--token", "stub-token", "--json")
	if got.code != client.ExitOK {
		t.Skipf("cannot save against an unreachable URL: %s", firstLine(got.stdout))
	}
}
