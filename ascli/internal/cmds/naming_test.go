package cmds

import (
	"strings"
	"testing"
)

// The command name is the whole user interface, so the rule that produces it is worth pinning down
// with examples rather than with a comment.
func TestCommandName(t *testing.T) {
	cases := map[string]string{
		"DEVICE_INFO":         "device-info",
		"DEVICE_ACTIONS":      "device-actions",
		"ON_DEVICE_ML":        "on-device-ml",
		"DOWNLOAD_MANAGER":    "download-manager",
		"shareText":           "share-text",
		"lastKnown":           "last-known",
		"NFC":                 "nfc",
		"QR_BARCODE":          "qr-barcode",
		"SIM_SUBSCRIPTION":    "sim-subscription",
		"checkPermission":     "check-permission",
		"pull":                "pull",
		"get":                 "get",
		"DO_NOT_DISTURB":      "do-not-disturb",
		"findFaces":           "find-faces",
		"requiresUserConsent": "requires-user-consent",
		"aCapabilityWith_Mix": "a-capability-with-mix",
		"ALLCAPS":             "allcaps",
		"trailing_":           "trailing",
		"_leading":            "leading",
		"double__underscore":  "double-underscore",
		"ALLCAPS_WITH_UNDER":  "allcaps-with-under",
	}

	for input, want := range cases {
		if got := CommandName(input); got != want {
			t.Errorf("CommandName(%q) = %q, want %q", input, got, want)
		}
	}
}

// A name that arrives as "d-e-v-i-c-e" is the failure mode worth guarding hardest: it looks
// plausible enough to ship and makes every command untypable.
func TestCommandNameDoesNotDashEveryLetter(t *testing.T) {
	got := CommandName("DEVICE_INFO")
	if strings.Count(got, "-") != 1 {
		t.Errorf("CommandName(\"DEVICE_INFO\") = %q, expected exactly one dash", got)
	}
	for _, r := range got {
		if r == '_' {
			t.Errorf("CommandName(\"DEVICE_INFO\") = %q, kept an underscore", got)
		}
	}
}

// Names must be safe to type on a shell and unique enough to dispatch on.
func TestCommandNamesAreShellSafe(t *testing.T) {
	for _, cmd := range GeneratedCommands() {
		parts := strings.SplitN(cmd.Path, " ", 2)
		if len(parts) != 2 {
			t.Fatalf("malformed path %q", cmd.Path)
		}
		for _, part := range parts {
			if part == "" {
				t.Errorf("empty name in %q", cmd.Path)
			}
			if strings.ContainsAny(part, " \t\n\"'$`\\") {
				t.Errorf("name %q in %q needs quoting to type", part, cmd.Path)
			}
			if part != strings.ToLower(part) {
				t.Errorf("name %q in %q is not lower case", part, cmd.Path)
			}
		}
	}
}

// Two capabilities must never collapse onto the same command, or one silently shadows the other.
func TestCommandPathsAreUnique(t *testing.T) {
	seen := map[string]string{}
	for _, cmd := range GeneratedCommands() {
		if previous, clash := seen[cmd.Path]; clash {
			t.Errorf("command %q is produced by both %s and %s", cmd.Path, previous, cmd.Capability+"."+cmd.Action)
		}
		seen[cmd.Path] = cmd.Capability + "." + cmd.Action
	}
}

// The generated table and the fixture are the same source; this is what makes drift a build failure
// rather than a surprise at runtime.
func TestGeneratedCommandsMatchThePlatform(t *testing.T) {
	if err := CheckGeneration(); err != nil {
		t.Fatalf("%v", err)
	}
}

func TestEveryDocumentedActionHasACommand(t *testing.T) {
	docs, err := LoadFixture()
	if err != nil {
		t.Skipf("no docs fixture: %v", err)
	}
	want := 0
	for _, capability := range docs.Capabilities {
		want += len(capability.Actions)
	}
	if got := len(GeneratedCommands()); got != want {
		t.Errorf("generated %d commands, the platform documents %d actions", got, want)
	}
}
