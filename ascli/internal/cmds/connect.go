package cmds

import (
	"bufio"
	"context"
	"fmt"
	"os"
	"strings"

	"github.com/sushantadhkr/android-star/ascli/internal/client"
	"github.com/sushantadhkr/android-star/ascli/internal/config"
)

// Connect remembers a platform and its token, checking both before saving.
//
// Checking first matters: a config saved without being tried is a config that fails later, at the
// moment someone is relying on it. A URL that does not answer, or a token the platform refuses, is
// reported here rather than at the next command.
func Connect(ctx context.Context, o *Output, url, token string, save bool) error {
	cfg, err := config.Load()
	if err != nil {
		return err
	}

	if url == "" {
		url = cfg.BaseURL
	}
	if url == "" {
		return &client.Error{
			Status:  400,
			Message: "No platform URL given and none remembered.",
			Hint:    "ascli connect http://<phone>:8899 --token <token>",
		}
	}
	if !strings.HasPrefix(url, "http://") && !strings.HasPrefix(url, "https://") {
		url = "http://" + url
	}
	url = strings.TrimRight(url, "/")

	if token == "" {
		token = cfg.Token
	}
	if token == "" {
		// Read from a pipe rather than asked for on the command line, where it would land in the
		// shell history and in the process list for anything on the machine to read.
		token, err = tokenFromStdin()
		if err != nil {
			return err
		}
	}
	if token == "" {
		return &client.Error{
			Status:  400,
			Message: "No token given and none remembered.",
			Hint: "Read the token on the phone in Settings → API access token, then either:\n" +
				"    ascli connect <url> --token <token>\n" +
				"    printf %s \"$TOKEN\" | ascli connect <url> --token-stdin",
		}
	}

	probe := client.New(url, token, cfg.SkipTLSVerify)
	docs, err := probe.Docs(ctx)
	if err != nil {
		return err
	}

	cfg.BaseURL = url
	cfg.Token = token
	if save {
		if err := cfg.Save(); err != nil {
			return err
		}
	}

	actions := 0
	for _, capability := range docs.Capabilities {
		actions += len(capability.Actions)
	}

	if o.JSON {
		return pretty(mustJSON(map[string]any{
			"baseUrl":  url,
			"token":    cfg.Redacted(),
			"features": len(docs.Capabilities),
			"actions":  actions,
			"saved":    save,
		}), o.Out)
	}

	fmt.Fprintf(o.Out, "  connected to %s\n", url)
	fmt.Fprintf(o.Out, "  token %s\n", cfg.Redacted())
	fmt.Fprintf(o.Out, "  %d Platform Features, %d actions\n", len(docs.Capabilities), actions)
	if save {
		fmt.Fprintf(o.Out, "  saved to %s\n", config.Path())
	}
	return nil
}

func tokenFromStdin() (string, error) {
	info, err := os.Stdin.Stat()
	if err != nil || info.Mode()&os.ModeCharDevice != 0 {
		return "", nil
	}
	line, err := bufio.NewReader(os.Stdin).ReadString('\n')
	if err != nil && line == "" {
		return "", nil
	}
	return strings.TrimSpace(line), nil
}

// Doctor answers one question: can this machine drive this platform right now?
//
// It is the first thing to run when something refuses, and it separates the reasons a command can
// fail — no URL, no token, cannot reach it, the CLI is out of date — which are otherwise
// indistinguishable from the same generic error.
func Doctor(ctx context.Context, c *client.Client, o *Output) error {
	type check struct {
		Name   string `json:"name"`
		OK     bool   `json:"ok"`
		Detail string `json:"detail,omitempty"`
	}

	checks := []check{}
	failures := 0
	add := func(name string, ok bool, detail string) {
		checks = append(checks, check{Name: name, OK: ok, Detail: detail})
		if !ok {
			failures++
		}
	}

	add("base url", c.BaseURL() != "", orDash(c.BaseURL()))
	add("token present", c.HasToken(), orAny(c.TokenRedacted(), "none saved"))

	// Reachability is checked with no token, so "cannot reach it" and "it does not know us" stay
	// two separate answers with two different fixes.
	reachable := false
	if c.BaseURL() != "" {
		if _, err := client.New(c.BaseURL(), "", false).Raw(ctx, "GET", "/", nil); err == nil {
			reachable = true
		}
	}
	add("reachable", reachable, orDash(c.BaseURL()))

	features, actions := 0, 0
	master := false

	docs, docErr := c.Docs(ctx)
	if docErr != nil {
		// An unauthenticated failure and an unreachable one are told apart by the platform's own
		// status, so the reader is not left guessing which half is broken.
		add("token accepted", false, summariseError(docErr))
	} else {
		add("token accepted", true, "")
		for _, capability := range docs.Capabilities {
			features++
			actions += len(capability.Actions)
		}
		if cat, err := c.Catalogue(ctx); err == nil {
			master = cat.MasterEnabled
			add("master switch", true, Mark(master))
		}
	}

	drift := Drift{}
	if docs != nil {
		drift = Compare(docs)
		add("cli up to date", drift.Empty(), drift.Summary())
	}

	payload := map[string]any{
		"baseUrl":   c.BaseURL(),
		"features":  features,
		"actions":   actions,
		"masterOn":  master,
		"reachable": reachable,
		"checks":    checks,
		"drift":     drift,
		"upToDate":  drift.Empty(),
	}

	if o.JSON {
		if err := pretty(mustJSON(payload), o.Out); err != nil {
			return err
		}
	} else {
		rows := make([][]string, 0, len(checks))
		for _, ch := range checks {
			rows = append(rows, []string{ch.Name, Mark(ch.OK), orDash(ch.Detail)})
		}
		o.Table([]string{"CHECK", "RESULT", "DETAIL"}, rows)
		fmt.Fprintf(o.Out, "\n  %d Platform Features, %d actions, master switch %s\n",
			features, actions, Mark(master))

		if !drift.Empty() {
			if len(drift.Missing) > 0 {
				fmt.Fprintf(o.Out, "  the platform has %d action(s) this CLI does not: %s\n",
					len(drift.Missing), strings.Join(trimList(drift.Missing, 6), ", "))
			}
			if len(drift.Extra) > 0 {
				fmt.Fprintf(o.Out, "  this CLI has %d action(s) the platform does not: %s\n",
					len(drift.Extra), strings.Join(trimList(drift.Extra, 6), ", "))
			}
			for _, changed := range drift.Changed {
				fmt.Fprintf(o.Out, "  %s: %s\n", changed.Command, changed.Reason)
			}
			fmt.Fprintf(o.Out, "  fix it with:  make -C ascli generate\n")
		}
	}

	if failures > 0 {
		return errSilent
	}
	return nil
}

func trimList(items []string, max int) []string {
	if len(items) <= max {
		return items
	}
	return append(append([]string{}, items[:max]...), fmt.Sprintf("+%d more", len(items)-max))
}

func summariseError(err error) string {
	var apiErr *client.Error
	if asAPIError(err, &apiErr) {
		return fmt.Sprintf("HTTP %d: %s", apiErr.Status, apiErr.Message)
	}
	return err.Error()
}

func orDash(s string) string {
	if s == "" {
		return "—"
	}
	return s
}

func orAny(value, fallback string) string {
	if value == "" {
		return fallback
	}
	return value
}

// errSilent marks a failure whose message has already been printed, so it is not printed twice.
var errSilent = &silentError{}

type silentError struct{}

func (e *silentError) Error() string { return "" }

// IsSilent reports whether the error was already reported and needs no further message.
func IsSilent(err error) bool {
	_, ok := err.(*silentError)
	return ok
}
