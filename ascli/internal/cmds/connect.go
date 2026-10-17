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

// Connect remembers a platform under a name, and checks it before saving.
//
// Checking first matters: a config saved without being tried is a config that fails later, at the
// moment someone is relying on it. A URL that does not answer, or a token the platform refuses, is
// reported here rather than at the next command.
//
// The name is what makes the rest of this worth it. One phone needs none, and gets the implicit one;
// a second phone makes every command state which it means, rather than leaving a URL and a token to
// retype.
func Connect(ctx context.Context, o *Output, name, url, token string, save bool) error {
	cfg, err := config.Load()
	if err != nil {
		return err
	}

	if name == "" {
		name = config.ImplicitDevice
	}
	if url == "" {
		return &client.Error{
			Status:  400,
			Message: "No platform URL given.",
			Hint: fmt.Sprintf("ascli connect %s <url> --token <token>\n"+
				"  ascli connect %s               for a second phone", name, name),
		}
	}
	if !strings.HasPrefix(url, "http://") && !strings.HasPrefix(url, "https://") {
		url = "http://" + url
	}
	url = strings.TrimRight(url, "/")

	// An existing device keeps its token when a new one is not given, so reconnecting to a known
	// phone does not require retyping the secret.
	if token == "" {
		if existing, ok := cfg.Devices[name]; ok {
			token = existing.Token
		}
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
			Message: "No token given.",
			Hint: "Read the token on the phone in Settings → API access token, then either:\n" +
				fmt.Sprintf("    ascli connect %s <url> --token <token>\n", name) +
				fmt.Sprintf("    printf %%s \"$TOKEN\" | ascli connect %s <url> --token-stdin", name),
		}
	}

	replacing := false
	if _, exists := cfg.Devices[name]; exists {
		replacing = true
	}

	probe := client.New(name, url, token, false)
	docs, err := probe.Docs(ctx)
	if err != nil {
		return err
	}

	device := config.Device{BaseURL: url, Token: token}
	if save {
		cfg.Put(name, device)
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
			"device":   name,
			"baseUrl":  url,
			"token":    device.Redacted(),
			"features": len(docs.Capabilities),
			"actions":  actions,
			"saved":    save,
			"replaced": replacing,
		}), o)
	}

	verb := "connected to"
	if replacing {
		verb = "reconnected to"
	}
	fmt.Fprintf(o, "  %s %s as '%s'\n", verb, url, name)
	fmt.Fprintf(o, "  token %s\n", device.Redacted())
	fmt.Fprintf(o, "  %d Platform Features, %d actions\n", len(docs.Capabilities), actions)

	switch {
	case !save:
		fmt.Fprintf(o, "  not saved. Use --save to keep it, or --device %s to use it this once.\n", name)
	case len(cfg.Names()) > 1:
		fmt.Fprintf(o, "  saved. Name it on any command:  ascli --device %s <command>\n", name)
		fmt.Fprintf(o, "  or make it the default:       ascli devices default %s\n", name)
	default:
		fmt.Fprintf(o, "  saved to %s\n", config.Path())
		fmt.Fprintf(o, "  it is your only device, so commands need no --device\n")
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

// Devices lists what is connected, and reaches each one so a stale entry is obvious rather than
// discovered later at the moment a command matters.
func Devices(ctx context.Context, o *Output) error {
	cfg, err := config.Load()
	if err != nil {
		return err
	}

	names := cfg.Names()
	if len(names) == 0 {
		if o.JSON {
			return pretty(mustJSON(map[string]any{"devices": []any{}, "default": ""}), o)
		}
		fmt.Fprintf(o, "  no devices connected\n")
		fmt.Fprintf(o, "  connect one:  ascli connect %s <url> --token <token>\n", config.ImplicitDevice)
		return nil
	}

	type entry struct {
		Name      string `json:"name"`
		BaseURL   string `json:"baseUrl"`
		Token     string `json:"token"`
		Default   bool   `json:"default"`
		Reachable bool   `json:"reachable"`
		Features  int    `json:"features"`
		Actions   int    `json:"actions"`
		Error     string `json:"error,omitempty"`
	}

	entries := make([]entry, 0, len(names))
	for _, name := range names {
		device := cfg.Devices[name]
		item := entry{
			Name:    name,
			BaseURL: device.BaseURL,
			Token:   device.Redacted(),
			Default: cfg.Default == name,
		}

		// One unreachable phone must not hide the state of the others, so each is asked separately
		// and a failure is reported against that device rather than ending the listing.
		probe := client.New(name, device.BaseURL, device.Token, device.SkipTLSVerify)
		docs, err := probe.Docs(ctx)
		if err != nil {
			item.Error = err.Error()
		} else {
			item.Reachable = true
			item.Features = len(docs.Capabilities)
			for _, capability := range docs.Capabilities {
				item.Actions += len(capability.Actions)
			}
		}
		entries = append(entries, item)
	}

	if o.JSON {
		return pretty(mustJSON(map[string]any{"devices": entries, "default": cfg.Default}), o)
	}

	rows := make([][]string, 0, len(entries))
	for _, item := range entries {
		marker := ""
		if item.Default {
			marker = " (default)"
		}
		state := "unreachable"
		if item.Reachable {
			state = fmt.Sprintf("%d features", item.Features)
		}
		detail := item.BaseURL
		if item.Error != "" {
			detail = item.Error
		}
		rows = append(rows, []string{item.Name + marker, state, detail, item.Token})
	}
	o.Table([]string{"DEVICE", "STATE", "DETAIL", "TOKEN"}, rows)
	return nil
}

// RemoveDevice forgets one device and its token.
func RemoveDevice(o *Output, name string) error {
	cfg, err := config.Load()
	if err != nil {
		return err
	}
	if !cfg.Remove(name) {
		return &client.Error{
			Status:  404,
			Message: fmt.Sprintf("No device called '%s'.", name),
			Hint:    fmt.Sprintf("Connected: %s", strings.Join(cfg.Names(), ", ")),
		}
	}
	if err := cfg.Save(); err != nil {
		return err
	}
	if o.JSON {
		return pretty(mustJSON(map[string]any{"removed": name, "default": cfg.Default}), o)
	}
	fmt.Fprintf(o, "  forgot '%s'\n", name)
	if cfg.Default == "" && len(cfg.Names()) == 1 {
		fmt.Fprintf(o, "  '%s' is your only device, so commands need no --device\n", cfg.Names()[0])
	}
	return nil
}

// SetDefaultDevice names the device used when --device is not given.
func SetDefaultDevice(o *Output, name string) error {
	cfg, err := config.Load()
	if err != nil {
		return err
	}
	if _, ok := cfg.Devices[name]; !ok {
		return &client.Error{
			Status:  404,
			Message: fmt.Sprintf("No device called '%s'.", name),
			Hint:    fmt.Sprintf("Connected: %s", strings.Join(cfg.Names(), ", ")),
		}
	}
	cfg.Default = name
	if err := cfg.Save(); err != nil {
		return err
	}
	if o.JSON {
		return pretty(mustJSON(map[string]any{"default": name}), o)
	}
	fmt.Fprintf(o, "  '%s' is the default\n", name)
	return nil
}

// Doctor answers one question: can this machine drive these platforms right now?
//
// It is the first thing to run when something refuses, and it separates the reasons a command can
// fail — no device, no token, cannot reach it, the CLI is out of date — which are otherwise
// indistinguishable from the same generic error.
//
// With no device named it checks every one, because with several phones the useful question is
// "which of these can I reach", not "can I reach the one I happened to pick".
func Doctor(ctx context.Context, o *Output, selected string) error {
	cfg, err := config.Load()
	if err != nil {
		return err
	}

	type check struct {
		Name   string `json:"name"`
		OK     bool   `json:"ok"`
		Detail string `json:"detail,omitempty"`
	}

	type report struct {
		Device    string  `json:"device"`
		BaseURL   string  `json:"baseUrl"`
		Reachable bool    `json:"reachable"`
		Features  int     `json:"features"`
		Actions   int     `json:"actions"`
		MasterOn  bool    `json:"masterOn"`
		UpToDate  bool    `json:"upToDate"`
		Drift     Drift   `json:"drift"`
		Checks    []check `json:"checks"`
		OK        bool    `json:"ok"`
	}

	names := cfg.Names()
	if selected != "" {
		names = []string{selected}
	}
	if len(names) == 0 {
		return &config.UnknownDeviceError{}
	}

	reports := make([]report, 0, len(names))
	failures := 0

	for _, name := range names {
		device := cfg.Devices[name]
		if device == nil {
			return &config.UnknownDeviceError{Name: name, Known: cfg.Names()}
		}

		c := client.New(name, device.BaseURL, device.Token, device.SkipTLSVerify)
		rep := report{Device: name, BaseURL: device.BaseURL}

		rep.Checks = append(rep.Checks,
			check{Name: "token present", OK: c.HasToken(), Detail: orAny(c.TokenRedacted(), "none saved")})

		// Reachability is asked with no token, so "cannot reach it" and "it does not know us" stay
		// two separate answers with two different fixes.
		if _, err := client.New(name, device.BaseURL, "", false).Raw(ctx, "GET", "/", nil); err == nil {
			rep.Reachable = true
			rep.Checks = append(rep.Checks, check{Name: "reachable", OK: true, Detail: device.BaseURL})
		} else {
			rep.Checks = append(rep.Checks,
				check{Name: "reachable", OK: false, Detail: device.BaseURL})
		}

		docs, docErr := c.Docs(ctx)
		if docErr != nil {
			rep.Checks = append(rep.Checks,
				check{Name: "token accepted", OK: false, Detail: summariseError(docErr)})
		} else {
			rep.Checks = append(rep.Checks, check{Name: "token accepted", OK: true})
			for _, capability := range docs.Capabilities {
				rep.Features++
				rep.Actions += len(capability.Actions)
			}
			if cat, err := c.Catalogue(ctx); err == nil {
				rep.MasterOn = cat.MasterEnabled
				rep.Checks = append(rep.Checks, check{Name: "master switch", OK: true, Detail: Mark(cat.MasterEnabled)})
			}
			drift := Compare(docs)
			rep.Drift = drift
			rep.UpToDate = drift.Empty()
			rep.Checks = append(rep.Checks, check{Name: "cli up to date", OK: drift.Empty(), Detail: drift.Summary()})
		}

		rep.OK = true
		for _, ch := range rep.Checks {
			if !ch.OK {
				rep.OK = false
				failures++
			}
		}
		reports = append(reports, rep)
	}

	if o.JSON {
		if err := pretty(mustJSON(map[string]any{
			"devices": reports,
			"default": cfg.Default,
			"ok":      failures == 0,
		}), o); err != nil {
			return err
		}
	} else {
		for _, rep := range reports {
			if len(reports) > 1 {
				fmt.Fprintf(o, "\n  %s  (%s)\n", rep.Device, rep.BaseURL)
			}
			rows := make([][]string, 0, len(rep.Checks))
			for _, ch := range rep.Checks {
				rows = append(rows, []string{ch.Name, Mark(ch.OK), orDash(ch.Detail)})
			}
			o.Table([]string{"CHECK", "RESULT", "DETAIL"}, rows)
			fmt.Fprintf(o, "  %d Platform Features, %d actions, master switch %s\n",
				rep.Features, rep.Actions, Mark(rep.MasterOn))

			if !rep.UpToDate && rep.Drift.Detail() != "" {
				fmt.Fprintf(o, "  %s\n", rep.Drift.Detail())
				fmt.Fprintf(o, "  fix it with:  make -C ascli generate\n")
			}
		}
	}

	if failures > 0 {
		return errSilent
	}
	return nil
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
