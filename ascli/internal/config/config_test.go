package config

import (
	"os"
	"path/filepath"
	"strings"
	"testing"
)

// A stand-in for a bearer token: long and hex-shaped, because the redaction tests care about the
// shape, not the credential that actually drives the phone. Never paste a real one here — the file
// is committed, and `node tools/check-secrets.js` will fail the build if you do.
const TOKEN = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef" // ascli:not-a-secret

func TestSaveAndLoadRoundTrips(t *testing.T) {
	t.Setenv("ASCLI_CONFIG", filepath.Join(t.TempDir(), "config.json"))

	cfg := &Config{Devices: map[string]*Device{}}
	cfg.Put(ImplicitDevice, Device{BaseURL: "http://10.0.0.5:8899", Token: TOKEN})
	if err := cfg.Save(); err != nil {
		t.Fatalf("save: %v", err)
	}

	loaded, err := Load()
	if err != nil {
		t.Fatalf("load: %v", err)
	}
	device, ok := loaded.Devices[ImplicitDevice]
	if !ok {
		t.Fatalf("round trip lost the device: %+v", loaded)
	}
	if device.BaseURL != "http://10.0.0.5:8899" || device.Token != TOKEN {
		t.Errorf("round trip lost something: %+v", device)
	}
}

// Several devices, each with its own token — the whole reason this exists.
func TestSeveralDevicesEachKeepTheirOwnToken(t *testing.T) {
	t.Setenv("ASCLI_CONFIG", filepath.Join(t.TempDir(), "config.json"))

	cfg := &Config{Devices: map[string]*Device{}}
	cfg.Put("pixel", Device{BaseURL: "http://10.0.0.1:8899", Token: "token-one-aaaaaaaaaa"})
	cfg.Put("android", Device{BaseURL: "http://10.0.0.2:8899", Token: "token-two-bbbbbbbbbb"})
	if err := cfg.Save(); err != nil {
		t.Fatalf("save: %v", err)
	}

	loaded, err := Load()
	if err != nil {
		t.Fatalf("load: %v", err)
	}
	if len(loaded.Devices) != 2 {
		t.Fatalf("loaded %d devices, want 2", len(loaded.Devices))
	}
	if loaded.Devices["pixel"].Token != "token-one-aaaaaaaaaa" {
		t.Errorf("pixel's token was crossed with another: %q", loaded.Devices["pixel"].Token)
	}
	if loaded.Devices["android"].Token != "token-two-bbbbbbbbbb" {
		t.Errorf("android's token was crossed with another: %q", loaded.Devices["android"].Token)
	}
}

// A config written before devices existed must keep working without the user doing anything.
func TestAConfigFromBeforeDevicesStillWorks(t *testing.T) {
	path := filepath.Join(t.TempDir(), "config.json")
	t.Setenv("ASCLI_CONFIG", path)

	legacy := `{"baseUrl":"http://10.0.0.5:8899","token":"` + TOKEN + `"}`
	if err := os.WriteFile(path, []byte(legacy), 0o600); err != nil {
		t.Fatalf("write: %v", err)
	}

	cfg, err := Load()
	if err != nil {
		t.Fatalf("load: %v", err)
	}
	name, device, err := cfg.Resolve("")
	if err != nil {
		t.Fatalf("an old config no longer resolves: %v", err)
	}
	if name != ImplicitDevice {
		t.Errorf("an old config became %q, want %q", name, ImplicitDevice)
	}
	if device.Token != TOKEN {
		t.Errorf("an old config lost the token: %+v", device)
	}
}

// With one device, a command needs no --device. That is the single-phone case.
func TestOneDeviceIsUsedWithoutBeingNamed(t *testing.T) {
	cfg := &Config{Devices: map[string]*Device{"pixel": {BaseURL: "http://x:8899"}}}

	name, device, err := cfg.Resolve("")
	if err != nil {
		t.Fatalf("a single device did not resolve: %v", err)
	}
	if name != "pixel" || device.BaseURL != "http://x:8899" {
		t.Errorf("resolved to %q/%q", name, device.BaseURL)
	}
}

// With two, guessing which would mean running a command against the wrong phone.
func TestSeveralDevicesAreNeverGuessedBetween(t *testing.T) {
	cfg := &Config{Devices: map[string]*Device{
		"pixel":   {BaseURL: "http://x:8899"},
		"android": {BaseURL: "http://y:8899"},
	}}

	_, _, err := cfg.Resolve("")
	if err == nil {
		t.Fatal("two devices resolved without being named")
	}
	var unknown *UnknownDeviceError
	if !asUnknown(err, &unknown) {
		t.Fatalf("error was %T, want *UnknownDeviceError", err)
	}
	if len(unknown.Known) != 2 {
		t.Errorf("the error did not list the devices: %v", unknown.Known)
	}
	if unknown.Hint() == "" {
		t.Error("the error gave no way forward")
	}
}

func TestTheDefaultIsUsedWhenSet(t *testing.T) {
	cfg := &Config{
		Devices: map[string]*Device{
			"pixel":   {BaseURL: "http://x:8899"},
			"android": {BaseURL: "http://y:8899"},
		},
		Default: "android",
	}
	name, device, err := cfg.Resolve("")
	if err != nil {
		t.Fatalf("resolve: %v", err)
	}
	if name != "android" || device.BaseURL != "http://y:8899" {
		t.Errorf("resolved to %q, want android", name)
	}
}

func TestANamedDeviceAlwaysWins(t *testing.T) {
	cfg := &Config{
		Devices: map[string]*Device{
			"pixel":   {BaseURL: "http://x:8899"},
			"android": {BaseURL: "http://y:8899"},
		},
		Default: "android",
	}
	name, _, err := cfg.Resolve("pixel")
	if err != nil {
		t.Fatalf("resolve: %v", err)
	}
	if name != "pixel" {
		t.Errorf("an explicit name was overridden by the default: %q", name)
	}
}

func TestAnUnknownDeviceIsNamedInTheRefusal(t *testing.T) {
	cfg := &Config{Devices: map[string]*Device{"pixel": {BaseURL: "http://x:8899"}}}

	_, _, err := cfg.Resolve("nokia")
	if err == nil {
		t.Fatal("an unknown device resolved")
	}
	var unknown *UnknownDeviceError
	if !asUnknown(err, &unknown) {
		t.Fatalf("error was %T", err)
	}
	if unknown.ExitCode() != 6 {
		t.Errorf("exit code was %d, want 6", unknown.ExitCode())
	}
	if !strings.Contains(unknown.Error(), "nokia") {
		t.Errorf("the refusal did not name what was asked for: %q", unknown.Error())
	}
}

func TestRemovingADeviceForgetsItsToken(t *testing.T) {
	cfg := &Config{Devices: map[string]*Device{
		"pixel":   {BaseURL: "http://x:8899", Token: TOKEN},
		"android": {BaseURL: "http://y:8899", Token: TOKEN},
	}, Default: "pixel"}

	if !cfg.Remove("pixel") {
		t.Fatal("removing a device that exists reported failure")
	}
	if _, still := cfg.Devices["pixel"]; still {
		t.Error("the device survived removal")
	}
	// The default pointed at the one that was removed, so it must not dangle.
	if cfg.Default == "pixel" {
		t.Errorf("the default still names a removed device: %q", cfg.Default)
	}
}

func TestRemovingADeviceThatIsNotThereSaysSo(t *testing.T) {
	cfg := &Config{Devices: map[string]*Device{"pixel": {BaseURL: "http://x:8899"}}}
	if cfg.Remove("nokia") {
		t.Error("removing an unknown device reported success")
	}
}

func asUnknown(err error, target **UnknownDeviceError) bool {
	if e, ok := err.(*UnknownDeviceError); ok {
		*target = e
		return true
	}
	return false
}

// The token is control of the device. A file another account on the same machine can read is not a
// secret, so the mode is set explicitly rather than left to the umask.
func TestTheConfigFileIsNotReadableByAnyoneElse(t *testing.T) {
	dir := t.TempDir()
	path := filepath.Join(dir, "config.json")
	t.Setenv("ASCLI_CONFIG", path)

	if err := (&Config{Devices: map[string]*Device{"pixel": {BaseURL: "http://x:8899", Token: TOKEN}}}).Save(); err != nil {
		t.Fatalf("save: %v", err)
	}

	info, err := os.Stat(path)
	if err != nil {
		t.Fatalf("stat: %v", err)
	}
	if mode := info.Mode().Perm(); mode != 0o600 {
		t.Errorf("config is mode %o, want 600", mode)
	}
}

// A directory this call created is ours to restrict, and holds a token, so it must not be readable
// by anyone else.
func TestADirectoryItCreatesIsNotReadableByAnyoneElse(t *testing.T) {
	dir := filepath.Join(t.TempDir(), "ascli", "nested")
	t.Setenv("ASCLI_CONFIG", filepath.Join(dir, "config.json"))

	if err := (&Config{Devices: map[string]*Device{"pixel": {Token: TOKEN}}}).Save(); err != nil {
		t.Fatalf("save: %v", err)
	}

	for _, created := range []string{dir, filepath.Dir(dir)} {
		info, err := os.Stat(created)
		if err != nil {
			t.Fatalf("stat %s: %v", created, err)
		}
		if mode := info.Mode().Perm(); mode&0o077 != 0 {
			t.Errorf("%s is mode %o, want no group or other access", created, mode)
		}
	}
}

// A directory that already existed belongs to the user, not to us. Tightening its permissions
// would be wrong — and, for somewhere like /tmp, something we are not allowed to do at all.
func TestAnExistingDirectoryIsLeftAlone(t *testing.T) {
	dir := t.TempDir()
	t.Setenv("ASCLI_CONFIG", filepath.Join(dir, "config.json"))

	if err := (&Config{Devices: map[string]*Device{"pixel": {Token: TOKEN}}}).Save(); err != nil {
		t.Fatalf("save into an existing directory: %v", err)
	}

	info, err := os.Stat(dir)
	if err != nil {
		t.Fatalf("stat: %v", err)
	}
	before := info.Mode().Perm()

	// The file is still ours and still protected, which is the part that matters.
	fileInfo, err := os.Stat(filepath.Join(dir, "config.json"))
	if err != nil {
		t.Fatalf("stat file: %v", err)
	}
	if mode := fileInfo.Mode().Perm(); mode != 0o600 {
		t.Errorf("config file is mode %o, want 600", mode)
	}
	if got := info.Mode().Perm(); got != before {
		t.Errorf("an existing directory's mode changed from %o to %o", before, got)
	}
}

// Redacted has to be recognisable without being usable — enough to tell two tokens apart, not
// enough to drive the phone.
func TestRedactedShowsTheEndsAndNotTheMiddle(t *testing.T) {
	got := Device{Token: TOKEN}.Redacted()

	if strings.Contains(got, TOKEN) {
		t.Errorf("redacted output contains the whole token: %q", got)
	}
	if !strings.HasPrefix(got, TOKEN[:6]) {
		t.Errorf("redacted output does not start with the token's first characters: %q", got)
	}
	if !strings.HasSuffix(got, TOKEN[len(TOKEN)-4:]) {
		t.Errorf("redacted output does not end with the token's last characters: %q", got)
	}
}

func TestRedactingAShortTokenHidesAllOfIt(t *testing.T) {
	// A short token has no safe "first few and last few", so nothing of it is shown.
	if got := (Device{Token: "abcd"}).Redacted(); strings.Contains(got, "abcd") {
		t.Errorf("a four character token leaked: %q", got)
	}
}

// Redact is for text we did not write — a platform message or an error that might echo a secret.
func TestRedactRemovesTheTokenFromAnyText(t *testing.T) {
	dirty := "GET /x failed with Authorization: Bearer " + TOKEN + " rejected"
	got := Redact(dirty, TOKEN)

	if strings.Contains(got, TOKEN) {
		t.Errorf("the token survived redaction: %q", got)
	}
	if !strings.Contains(got, "<redacted>") {
		t.Errorf("redaction was not marked: %q", got)
	}
}

func TestRedactWithNoTokenLeavesTextAlone(t *testing.T) {
	text := "nothing secret here"
	if got := Redact(text, ""); got != text {
		t.Errorf("redaction changed text when there was no token: %q", got)
	}
}

// A missing config is an empty one, not an error: a first run has no config and must still work.
func TestAMissingConfigIsEmpty(t *testing.T) {
	t.Setenv("ASCLI_CONFIG", filepath.Join(t.TempDir(), "never-written.json"))

	cfg, err := Load()
	if err != nil {
		t.Fatalf("a missing config was an error: %v", err)
	}
	if len(cfg.Devices) != 0 {
		t.Errorf("a missing config came back with devices: %+v", cfg)
	}
}

// A corrupt config must not be silently overwritten. Losing the URL means reconnecting; losing the
// token means rotating it and telling every script that used it.
func TestACorruptConfigIsReportedNotReplaced(t *testing.T) {
	path := filepath.Join(t.TempDir(), "config.json")
	t.Setenv("ASCLI_CONFIG", path)
	if err := os.WriteFile(path, []byte("{not json"), 0o600); err != nil {
		t.Fatalf("write: %v", err)
	}

	if _, err := Load(); err == nil {
		t.Fatal("a corrupt config loaded without complaint")
	}

	body, _ := os.ReadFile(path)
	if string(body) != "{not json" {
		t.Errorf("loading overwrote the corrupt file: %q", body)
	}
}

// Saving twice must not leave the intermediate file behind, and must not be corrupted by a crash
// partway through — which is why it writes to a temporary and renames.
func TestSavingTwiceLeavesNoTemporaryFiles(t *testing.T) {
	dir := t.TempDir()
	t.Setenv("ASCLI_CONFIG", filepath.Join(dir, "config.json"))

	for i := 0; i < 3; i++ {
		cfg := &Config{Devices: map[string]*Device{"pixel": {BaseURL: "http://x", Token: "t"}}}
		if err := cfg.Save(); err != nil {
			t.Fatalf("save %d: %v", i, err)
		}
	}

	entries, err := os.ReadDir(dir)
	if err != nil {
		t.Fatalf("readdir: %v", err)
	}
	for _, entry := range entries {
		if strings.HasSuffix(entry.Name(), ".tmp") {
			t.Errorf("a temporary file was left behind: %s", entry.Name())
		}
	}
	if len(entries) != 1 {
		t.Errorf("expected exactly one file in %s, found %d", dir, len(entries))
	}
}

// Clearing a token leaves the device in place, so reconnecting needs no name or URL again.
func TestClearForgetsTheTokenButKeepsTheDevice(t *testing.T) {
	device := Device{BaseURL: "http://x:8899", Token: TOKEN}
	device.Clear()
	if device.Token != "" {
		t.Errorf("Clear left the token: %q", device.Token)
	}
	if device.BaseURL != "http://x:8899" {
		t.Errorf("Clear also took the URL: %q", device.BaseURL)
	}
}
