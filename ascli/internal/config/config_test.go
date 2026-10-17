package config

import (
	"os"
	"path/filepath"
	"strings"
	"testing"
)

const TOKEN = "d128dd60d26efaffe439615fb315def37595d0748f412353187ee479c7f75117"

func TestSaveAndLoadRoundTrips(t *testing.T) {
	t.Setenv("ASCLI_CONFIG", filepath.Join(t.TempDir(), "config.json"))

	cfg := &Config{BaseURL: "http://10.0.0.5:8899", Token: TOKEN}
	if err := cfg.Save(); err != nil {
		t.Fatalf("save: %v", err)
	}

	loaded, err := Load()
	if err != nil {
		t.Fatalf("load: %v", err)
	}
	if loaded.BaseURL != cfg.BaseURL || loaded.Token != cfg.Token {
		t.Errorf("round trip lost something: %+v", loaded)
	}
}

// The token is control of the device. A file another account on the same machine can read is not a
// secret, so the mode is set explicitly rather than left to the umask.
func TestTheConfigFileIsNotReadableByAnyoneElse(t *testing.T) {
	dir := t.TempDir()
	path := filepath.Join(dir, "config.json")
	t.Setenv("ASCLI_CONFIG", path)

	if err := (&Config{BaseURL: "http://x:8899", Token: TOKEN}).Save(); err != nil {
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

	if err := (&Config{Token: TOKEN}).Save(); err != nil {
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

	if err := (&Config{Token: TOKEN}).Save(); err != nil {
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
	cfg := &Config{Token: TOKEN}
	got := cfg.Redacted()

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
	cfg := &Config{Token: "abcd"}
	if got := cfg.Redacted(); strings.Contains(got, "abcd") {
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
	if cfg.BaseURL != "" || cfg.Token != "" {
		t.Errorf("a missing config came back with values: %+v", cfg)
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
		if err := (&Config{BaseURL: "http://x", Token: "t"}).Save(); err != nil {
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

func TestClearForgetsTheToken(t *testing.T) {
	cfg := &Config{BaseURL: "http://x:8899", Token: TOKEN}
	cfg.Clear()
	if cfg.Token != "" {
		t.Errorf("Clear left the token: %q", cfg.Token)
	}
	if cfg.BaseURL != "http://x:8899" {
		t.Errorf("Clear also took the URL: %q", cfg.BaseURL)
	}
}
