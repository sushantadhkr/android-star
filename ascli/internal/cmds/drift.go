package cmds

import (
	"context"
	"encoding/json"
	"fmt"
	"os"
	"path/filepath"
	"sort"
	"strings"

	"github.com/sushantadhkr/android-star/ascli/internal/client"
)

// mustJSON encodes something we constructed ourselves.
//
// Used only for values this tool built — never for a platform reply, which is passed through
// untouched. A failure here is a programming mistake, and hiding it would be worse than a panic.
func mustJSON(v any) []byte {
	encoded, err := json.Marshal(v)
	if err != nil {
		fmt.Fprintf(os.Stderr, "ascli: cannot encode output: %v\n", err)
		return []byte("{}")
	}
	return encoded
}

// Drift is how far the CLI and the platform have moved apart.
type Drift struct {
	// Missing are actions the platform offers that this CLI has no command for — the platform got
	// something new and the CLI has not been regenerated.
	Missing []string `json:"missing"`
	// Extra are commands this CLI has that the platform no longer offers — the CLI was built
	// against something that has since changed.
	Extra []string `json:"extra"`
	// Changed are commands that exist on both sides but whose parameters no longer match.
	Changed []ChangedCommand `json:"changed"`
}

// ChangedCommand names a command whose shape moved underneath it.
type ChangedCommand struct {
	Command string   `json:"command"`
	Reason  string   `json:"reason"`
	Missing []string `json:"missingFlags,omitempty"`
	Extra   []string `json:"extraFlags,omitempty"`
}

// Empty says whether the two sides agree completely.
func (d Drift) Empty() bool {
	return len(d.Missing) == 0 && len(d.Extra) == 0 && len(d.Changed) == 0
}

// Summary is one line for a human.
func (d Drift) Summary() string {
	if d.Empty() {
		return "this CLI matches the platform exactly"
	}
	parts := []string{}
	if len(d.Missing) > 0 {
		parts = append(parts, fmt.Sprintf("%d new on the platform", len(d.Missing)))
	}
	if len(d.Extra) > 0 {
		parts = append(parts, fmt.Sprintf("%d no longer offered", len(d.Extra)))
	}
	if len(d.Changed) > 0 {
		parts = append(parts, fmt.Sprintf("%d with different parameters", len(d.Changed)))
	}
	return strings.Join(parts, ", ")
}

// platformShape flattens a docs payload to command path → parameter names.
func platformShape(docs *client.Docs) map[string][]string {
	shape := map[string][]string{}
	if docs == nil {
		return shape
	}
	for _, capability := range docs.Capabilities {
		for _, action := range capability.Actions {
			names := make([]string, 0, len(action.Params))
			for _, p := range action.Params {
				names = append(names, p.Name)
			}
			shape[CommandName(capability.ID)+" "+CommandName(action.Name)] = names
		}
	}
	return shape
}

func builtShape() map[string][]string {
	shape := map[string][]string{}
	for _, cmd := range GeneratedCommands() {
		shape[cmd.Path] = cmd.ParamNames()
	}
	return shape
}

// Compare is the drift between a docs payload and the commands compiled into this binary.
func Compare(docs *client.Docs) Drift {
	platform := platformShape(docs)
	built := builtShape()
	result := Drift{}

	for key := range platform {
		if _, ok := built[key]; !ok {
			result.Missing = append(result.Missing, key)
		}
	}
	for key := range built {
		if _, ok := platform[key]; !ok {
			result.Extra = append(result.Extra, key)
		}
	}

	for key, platformParams := range platform {
		builtParams, ok := built[key]
		if !ok {
			continue
		}
		builtSet := map[string]bool{}
		for _, p := range builtParams {
			builtSet[p] = true
		}
		platformSet := map[string]bool{}
		for _, p := range platformParams {
			platformSet[p] = true
		}

		change := ChangedCommand{Command: key}
		for _, p := range platformParams {
			if !builtSet[p] {
				change.Missing = append(change.Missing, p)
			}
		}
		for _, p := range builtParams {
			if !platformSet[p] {
				change.Extra = append(change.Extra, p)
			}
		}
		if len(change.Missing) > 0 {
			change.Reason = "the platform now takes parameters this command does not"
		} else if len(change.Extra) > 0 {
			change.Reason = "this command has parameters the platform no longer takes"
		}
		if change.Reason != "" {
			result.Changed = append(result.Changed, change)
		}
	}

	sort.Strings(result.Missing)
	sort.Strings(result.Extra)
	sort.Slice(result.Changed, func(i, j int) bool {
		return result.Changed[i].Command < result.Changed[j].Command
	})
	return result
}

// Live compares this binary against a connected platform.
func Live(ctx context.Context, c *client.Client) (Drift, error) {
	docs, err := c.Docs(ctx)
	if err != nil {
		return Drift{}, err
	}
	return Compare(docs), nil
}

// fixtureCandidates are the places a docs payload can live, relative to any starting directory.
var fixtureCandidates = []string{
	filepath.Join("testdata", "docs.json"),
	filepath.Join("tools", "fixtures", "docs-sample.json"),
	filepath.Join("ascli", "testdata", "docs.json"),
	filepath.Join("..", "tools", "fixtures", "docs-sample.json"),
}

// FindFixture locates the docs payload, walking upward from the working directory.
//
// The upward walk is what lets the same lookup work from the repository root, from the ascli
// directory, and from inside a Go test — which runs with the package directory as its working
// directory. Hard-coding one relative path meant the generator found nothing when run by `go test`
// and only worked by accident from one particular directory.
func FindFixture() (string, error) {
	dir, err := os.Getwd()
	if err != nil {
		return "", err
	}
	for {
		for _, candidate := range fixtureCandidates {
			full := filepath.Join(dir, candidate)
			if _, err := os.Stat(full); err == nil {
				return full, nil
			}
		}
		parent := filepath.Dir(dir)
		if parent == dir {
			break
		}
		dir = parent
	}
	return "", fmt.Errorf("no docs fixture found (looked for testdata/docs.json and " +
		"tools/fixtures/docs-sample.json, walking up from the working directory)")
}

// LoadFixture reads the committed docs payload, so the CLI builds and tests with no phone attached.
func LoadFixture() (*client.Docs, error) {
	path, err := FindFixture()
	if err != nil {
		return nil, err
	}
	raw, err := os.ReadFile(path)
	if err != nil {
		return nil, fmt.Errorf("cannot read %s: %w", path, err)
	}
	docs := &client.Docs{}
	if err := json.Unmarshal(raw, docs); err != nil {
		return nil, fmt.Errorf("%s is not valid docs JSON: %w", path, err)
	}
	if len(docs.Capabilities) == 0 {
		return nil, fmt.Errorf("%s lists no capabilities, so there is nothing to generate from", path)
	}
	return docs, nil
}

// CheckGeneration fails when the committed commands no longer match the fixture.
//
// This is what turns "update the CLI when the platform changes" into a build failure rather than a
// thing to remember. Without a phone it compares the generated source against the committed file;
// with one, it can also be pointed at a live platform.
func CheckGeneration() error {
	docs, err := LoadFixture()
	if err != nil {
		return err
	}

	regenerated, err := Render(docs)
	if err != nil {
		return err
	}

	onDisk, err := os.ReadFile(GeneratedPath())
	if err != nil {
		return fmt.Errorf("cannot read %s: %w", GeneratedPath(), err)
	}

	if strings.TrimSpace(string(onDisk)) == strings.TrimSpace(regenerated) {
		return nil
	}

	drift := Compare(docs)
	var b strings.Builder
	b.WriteString("ascli's commands do not match the platform.\n")
	if len(drift.Missing) > 0 {
		fmt.Fprintf(&b, "  %d action(s) the platform has that this CLI does not:\n", len(drift.Missing))
		for _, name := range drift.Missing {
			fmt.Fprintf(&b, "    + %s\n", name)
		}
	}
	if len(drift.Extra) > 0 {
		fmt.Fprintf(&b, "  %d action(s) this CLI has that the platform does not:\n", len(drift.Extra))
		for _, name := range drift.Extra {
			fmt.Fprintf(&b, "    - %s\n", name)
		}
	}
	for _, changed := range drift.Changed {
		fmt.Fprintf(&b, "    ~ %s: %s\n", changed.Command, changed.Reason)
	}
	b.WriteString("\n  Fix it with:\n    make -C ascli generate\n")
	return fmt.Errorf("%s", b.String())
}

// DocsFromLiveOrFixture prefers the real platform and falls back to the bundled fixture.
//
// `ascli capabilities` has to work on a machine that has never connected to anything, because
// reading the API's shape is the first thing anyone does.
func DocsFromLiveOrFixture(ctx context.Context, c *client.Client) (*client.Docs, string, error) {
	if c != nil && c.BaseURL() != "" {
		if docs, err := c.Docs(ctx); err == nil {
			return docs, "live", nil
		}
	}
	docs, err := LoadFixture()
	if err != nil {
		return nil, "", err
	}
	return docs, "bundled", nil
}
