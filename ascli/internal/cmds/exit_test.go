package cmds

import (
	"errors"
	"fmt"
	"io"
	"strings"
	"testing"

	"github.com/sushantadhkr/android-star/ascli/internal/client"
)

// A mistake in the command line, a refusal from the platform and an unreachable phone are three
// different things. Collapsing them into one code sends people off debugging the wrong problem.
func TestEachKindOfFailureHasItsOwnExitCode(t *testing.T) {
	cases := []struct {
		name string
		err  error
		want int
	}{
		{
			name: "a usage mistake is usage, not a network problem",
			err:  Usagef("ascli help", "connect takes a name and a URL"),
			want: client.ExitUsage,
		},
		{
			name: "a platform refusal is a refusal",
			err:  &client.Error{Status: 400, Message: "Capability 'X' is switched off"},
			want: client.ExitRefused,
		},
		{
			name: "a bad token is auth",
			err:  &client.Error{Status: 401, Message: "Unauthorized"},
			want: client.ExitAuth,
		},
		{
			name: "a switched-off feature is auth",
			err:  &client.Error{Status: 403, Message: "Capability 'X' is switched off"},
			want: client.ExitAuth,
		},
		{
			name: "something that is not there is not found",
			err:  &client.Error{Status: 404, Message: "No download with id 'x'"},
			want: client.ExitNotFound,
		},
		{
			name: "an unclassified failure is the network code",
			err:  errors.New("dial tcp: connection refused"),
			want: client.ExitNetwork,
		},
	}

	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			got := ExitWith(discardOutput(), tc.err)
			if got != tc.want {
				t.Errorf("ExitWith(%v) = %d, want %d", tc.err, got, tc.want)
			}
		})
	}
}

// A usage error must be recognisable through wrapping, so a helper can add context about where it
// happened without losing the code that says what kind of failure it was.
func TestAnExitCodeSurvivesWrapping(t *testing.T) {
	original := Usagef("ascli help", "no url")
	wrapped := fmt.Errorf("while reading the config: %w", original)

	var coder ExitCoder
	if !errors.As(wrapped, &coder) {
		t.Fatal("a wrapped usage error was not recognised")
	}
	if coder.ExitCode() != client.ExitUsage {
		t.Errorf("wrapped code was %d, want %d", coder.ExitCode(), client.ExitUsage)
	}
	if !strings.Contains(wrapped.Error(), "while reading the config") {
		t.Errorf("the wrapping context was lost: %q", wrapped.Error())
	}
}

// A usage error carries the way out, because "wrong arguments" alone leaves the reader guessing.
func TestAUsageErrorShowsItsHint(t *testing.T) {
	err := Usagef("ascli connect --help", "connect takes a name and a URL")
	if err.ExitCode() != client.ExitUsage {
		t.Errorf("code was %d", err.ExitCode())
	}
	if got := err.Error(); got == "" || !contains(got, "ascli connect --help") {
		t.Errorf("the hint is missing from %q", got)
	}
}

func contains(haystack, needle string) bool {
	return len(haystack) >= len(needle) && (haystack == needle ||
		len(needle) == 0 ||
		indexOf(haystack, needle) >= 0)
}

func indexOf(haystack, needle string) int {
	for i := 0; i+len(needle) <= len(haystack); i++ {
		if haystack[i:i+len(needle)] == needle {
			return i
		}
	}
	return -1
}

// discardOutput is an Output writing nowhere, so a test can check a code without checking a layout.
func discardOutput() *Output {
	return &Output{JSON: true, Out: io.Discard, Err: io.Discard}
}
