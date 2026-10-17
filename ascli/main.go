// Command ascli drives an Android Star platform from a terminal.
//
// It is generated from the platform's own description of itself rather than hand-written, so every
// Platform Feature and every action it exposes is reachable without anyone maintaining a list. When
// the platform changes, `make generate` rebuilds that list; `make check` fails the build if nobody
// did.
package main

import (
	"context"
	"errors"
	"fmt"
	"os"
	"os/signal"
	"sort"
	"strings"
	"syscall"

	"github.com/sushantadhkr/android-star/ascli/internal/client"
	"github.com/sushantadhkr/android-star/ascli/internal/cmds"
	"github.com/sushantadhkr/android-star/ascli/internal/config"
)

// version is stamped at build time. Kept as a variable so `go build` works without any flags.
var version = "dev"

const usage = `ascli — drive an Android Star platform from your terminal

  ascli connect <url> --token <token>   remember a platform and prove the token works
  ascli doctor                          can this machine drive that platform right now
  ascli capabilities [filter]            every Platform Feature
  ascli capability <FEATURE>            one feature, every action, with examples
  ascli platform status                  the master switch and every feature's state
  ascli platform on|off                  the master switch
  ascli platform enable|disable <FEAT>  one Platform Feature
  ascli <feature> <action> [flags]       run any action the platform offers
  ascli invoke <FEAT> <action> [flags]   the same, by platform name — for actions newer than this build
  ascli pull <FEATURE> <id> -o <file>    save a file the platform produced
  ascli raw <METHOD> <path> [--body J]   any documented route, including Apps'
  ascli completion <shell>               shell completion

Every action is generated from the platform's own docs, so anything it offers works here:

  ascli camera capture -o shot.jpg
  ascli device-info get
  ascli download-manager fetch --url https://example.com/clip.mp4
  ascli notification post --title "Hi" --message "there"

Global flags:

  --json          machine-readable output (the default when stdout is not a terminal)
  --no-json       force the readable form, even when piping
  --verbose       show what is being sent, with the token redacted
  --token <t>     override the saved token for this command
  --url <u>       override the saved platform URL for this command
  --tls-skip-verify   accept a self-signed certificate, for an HTTPS platform
  -h, --help      this
      --version   the version

Exit codes:

  0  it worked
  1  the command was used wrongly
  2  the platform did not accept the token, or a feature is switched off
  3  the platform refused on purpose
  4  the platform could not be reached
  5  whatever was asked for does not exist
`

func main() {
	os.Exit(run(os.Args[1:]))
}

func run(args []string) int {
	if len(args) == 0 {
		fmt.Fprint(os.Stderr, usage)
		return client.ExitUsage
	}

	switch args[0] {
	case "-h", "--help", "help":
		fmt.Fprint(os.Stdout, usage)
		return client.ExitOK
	case "--version", "version":
		fmt.Fprintf(os.Stdout, "ascli %s\n", version)
		return client.ExitOK
	}

	// Ctrl-C cancels the context, so a long --watch or a slow pull stops tidily instead of
	// leaving the shell with ^C and an ambiguous outcome.
	ctx, cancel := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
	defer cancel()

	o := cmds.NewOutput(false, false)
	forced := false
	var flagErr error
	var rest []string
	for i := 0; i < len(args); i++ {
		arg := args[i]
		// Global flags are stripped wherever they appear, so `ascli camera capture --json` works
		// as well as `ascli --json camera capture`.
		switch arg {
		case "--json":
			o.JSON = true
			forced = true
			continue
		case "--no-json":
			// Needed because JSON is the default when stdout is a pipe, and without this there is
			// no way to ask for the readable form from a shell that redirects output — `ascli
			// capabilities | less` should still be readable.
			o.JSON = false
			forced = true
			continue
		case "--format":
			if i+1 < len(args) {
				switch args[i+1] {
				case "json":
					o.JSON = true
				case "text":
					o.JSON = false
				default:
					flagErr = fmt.Errorf("--format takes json or text, got %q", args[i+1])
				}
				forced = true
				i++
			}
			continue
		case "--verbose":
			o.Verbose = true
			continue
		}
		rest = append(rest, arg)
	}
	// A pipe means a script is reading, so JSON is the default rather than the exception — unless
	// the format was asked for either way.
	if !forced && !isTTY() {
		o.JSON = true
	}

	if flagErr != nil {
		fmt.Fprintf(o.Err, "  %s\n", flagErr.Error())
		return client.ExitUsage
	}

	if len(rest) == 0 {
		fmt.Fprint(os.Stderr, usage)
		return client.ExitUsage
	}

	baseURL, token, skipTLS := settings(rest)
	c := client.New(baseURL, token, skipTLS)

	err := dispatch(ctx, c, o, rest)
	if err == nil {
		return client.ExitOK
	}
	if cmds.IsSilent(err) {
		return client.ExitRefused
	}

	code := cmds.ExitWith(o, err)
	if errors.Is(err, context.Canceled) {
		fmt.Fprintf(o.Err, "  stopped\n")
		return client.ExitOK
	}
	return code
}

// settings resolves the base URL and token, with flags beating the saved config and the environment
// beating nothing at all.
func settings(args []string) (baseURL, token string, skipTLS bool) {
	cfg, err := config.Load()
	if err != nil {
		// A broken config must not stop `ascli doctor` or `ascli connect` from running, which are
		// the two commands that would fix it.
		cfg = &config.Config{}
	}
	baseURL = cfg.BaseURL
	token = cfg.Token
	skipTLS = cfg.SkipTLSVerify

	for i := 0; i < len(args); i++ {
		value := ""
		if i+1 < len(args) {
			value = args[i+1]
		}
		switch args[i] {
		case "--token", "--api-token":
			if value != "" {
				token = value
			}
		case "--url", "--base-url":
			if value != "" {
				baseURL = value
			}
		case "--tls-skip-verify":
			skipTLS = true
		}
	}

	if env := os.Getenv("ASCLI_TOKEN"); env != "" {
		token = env
	}
	if env := os.Getenv("ASCLI_URL"); env != "" {
		baseURL = env
	}
	return baseURL, token, skipTLS
}

func dispatch(ctx context.Context, c *client.Client, o *cmds.Output, args []string) error {
	command := args[0]

	switch command {
	case "connect":
		return connectCommand(ctx, o, args[1:])

	case "doctor":
		if c.BaseURL() == "" {
			return &client.Error{
				Status:  400,
				Message: "No platform connected.",
				Hint:    "ascli connect http://<phone>:8899 --token <token>",
			}
		}
		return cmds.Doctor(ctx, c, o)

	case "capabilities":
		filter := ""
		if len(args) > 1 && !strings.HasPrefix(args[1], "-") {
			filter = args[1]
		}
		return cmds.Capabilities(ctx, c, o, filter)

	case "capability":
		if len(args) < 2 {
			return usageError("which Platform Feature? Try: ascli capability CAMERA")
		}
		return cmds.Capability(ctx, c, o, args[1])

	case "platform":
		return platformCommand(ctx, c, o, args[1:])

	case "invoke":
		if len(args) < 3 {
			return usageError("needs a capability and an action. Try: ascli invoke DEVICE_INFO get")
		}
		flags, err := parseFlags(args[3:])
		if err != nil {
			return err
		}
		return cmds.Invoke(ctx, c, o, args[1], args[2], flags)

	case "pull":
		if len(args) < 3 {
			return usageError("needs a capability and an id. Try: ascli pull CAMERA cap_123 -o shot.jpg")
		}
		destination, err := outputFlag(args[3:])
		if err != nil {
			return err
		}
		return cmds.Save(ctx, c, o, args[1], args[2], destination)

	case "raw":
		if len(args) < 3 {
			return usageError("needs a method and a path. Try: ascli raw GET /api/capabilities")
		}
		return rawCommand(ctx, c, o, args[1], args[2], args[3:])

	case "completion":
		return completionCommand(args[1:])

	case "help":
		// `ascli help camera capture` is the same as `ascli camera capture --help`.
		if len(args) > 1 {
			return helpFor(ctx, c, o, args[1], args[2:]...)
		}
		fmt.Fprint(o, usage)
		return nil
	}

	// Two words: a feature and an action.
	if len(args) >= 2 {
		return generatedCommand(ctx, c, o, args[0], args[1], args[2:])
	}

	return unknownCommand(command, c)
}

func connectCommand(ctx context.Context, o *cmds.Output, args []string) error {
	if len(args) > 0 && (args[0] == "--help" || args[0] == "-h") {
		fmt.Fprintf(o, "  ascli connect <url> --token <token>\n")
		fmt.Fprintf(o, "  ascli connect <url> --token-stdin   # read the token from a pipe\n\n")
		fmt.Fprintf(o, "  The token is on the phone in Settings → API access token.\n")
		return nil
	}

	url, token := "", ""
	save := true
	for i := 0; i < len(args); i++ {
		next := ""
		if i+1 < len(args) {
			next = args[i+1]
		}
		switch args[i] {
		case "--token", "--api-token":
			token = next
			i++
		case "--token-stdin":
			i++
		case "--no-save":
			save = false
		default:
			if !strings.HasPrefix(args[i], "-") && url == "" {
				url = args[i]
			}
		}
	}
	return cmds.Connect(ctx, o, url, token, save)
}

func platformCommand(ctx context.Context, c *client.Client, o *cmds.Output, args []string) error {
	if len(args) == 0 || args[0] == "--help" || args[0] == "-h" {
		fmt.Fprintf(o, "  ascli platform status                  every feature and the master switch\n")
		fmt.Fprintf(o, "  ascli platform on | off                 the master switch\n")
		fmt.Fprintf(o, "  ascli platform enable <FEATURE>        switch one Platform Feature on\n")
		fmt.Fprintf(o, "  ascli platform disable <FEATURE>       switch one off — every action on it then returns 403\n\n")
		return nil
	}

	p := &cmds.Platform{Client: c, Out: o}

	switch args[0] {
	case "status":
		return p.Status(ctx)
	case "on", "enable-all":
		return p.SetMaster(ctx, true)
	case "off", "disable-all":
		return p.SetMaster(ctx, false)
	case "enable":
		if len(args) < 2 {
			return usageError("which Platform Feature? Try: ascli platform enable CAMERA")
		}
		return p.SetFeature(ctx, args[1], true)
	case "disable":
		if len(args) < 2 {
			return usageError("which Platform Feature? Try: ascli platform disable CAMERA")
		}
		return p.SetFeature(ctx, args[1], false)
	default:
		return usageError(fmt.Sprintf("unknown: platform %s", args[0]))
	}
}

func rawCommand(ctx context.Context, c *client.Client, o *cmds.Output, method, path string, args []string) error {
	body := ""
	for i := 0; i < len(args); i++ {
		if args[i] == "--body" && i+1 < len(args) {
			body = args[i+1]
			i++
		}
	}
	var payload []byte
	if body != "" {
		payload = []byte(body)
	}
	raw, err := c.Raw(ctx, method, path, payload)
	if err != nil {
		return err
	}
	return o.Result(raw)
}

func completionCommand(args []string) error {
	shell := "bash"
	if len(args) > 0 {
		shell = args[0]
	}
	script, err := cmds.Completion(shell)
	if err != nil {
		return err
	}
	fmt.Fprint(os.Stdout, script)
	return nil
}

// generatedCommand runs a feature and action, or shows its help.
func generatedCommand(ctx context.Context, c *client.Client, o *cmds.Output, feature, action string, args []string) error {
	path := cmds.CommandName(feature) + " " + cmds.CommandName(action)

	if wantsHelp(args) {
		return helpFor(ctx, c, o, feature, action)
	}

	flags, err := parseFlags(args)
	if err != nil {
		return err
	}

	cmd, found := cmds.FindCommand(path)
	if !found {
		// Not in the generated table — most likely the platform gained it since this build. Sent
		// anyway rather than refused, so an out-of-date CLI still works.
		return cmds.Invoke(ctx, c, o, feature, action, flags)
	}

	// `--watch` is handled here rather than generated, because it is a client-side behaviour and
	// has nothing to do with how the platform names its parameters.
	watched, err := boolFlag(args, "--watch")
	if err != nil {
		return err
	}
	if watched {
		settled, err := boolFlag(args, "--until")
		if err != nil {
			return err
		}
		states := []string{"complete", "failed", "cancelled"}
		if settled {
			states = append(states, "idle")
		}
		return cmds.Watch(ctx, c, o, cmd.Capability, cmd.Action, flags, states)
	}

	// A `-o` on an action that produces a file means: run it, then fetch what it made.
	if destination, ok, err := outputFlagOK(args); err != nil {
		return err
	} else if ok {
		raw, err := cmds.InvokeGenerated(ctx, c, cmd, flags)
		if err != nil {
			return err
		}
		id, err := cmds.IDFrom(raw)
		if err != nil {
			return err
		}
		return cmds.Save(ctx, c, o, cmd.Capability, id, destination)
	}

	return cmds.Run(ctx, c, o, cmd, flags)
}

func helpFor(ctx context.Context, c *client.Client, o *cmds.Output, feature string, action ...string) error {
	if len(action) == 0 {
		return cmds.Capability(ctx, c, o, feature)
	}
	// A feature and an action: show that one action, from the platform's own words.
	if cmd, ok := cmds.FindCommand(cmds.CommandName(feature) + " " + cmds.CommandName(action[0])); ok {
		fmt.Fprintf(o, "  ascli %s\n\n", cmd.Path)
		fmt.Fprintf(o, "  %s\n", cmd.Summary)
		if len(cmd.Params) == 0 {
			fmt.Fprintf(o, "\n  it takes no parameters.\n")
			return nil
		}
		fmt.Fprintln(o)
		for _, p := range cmd.Params {
			required := ""
			if p.Required {
				required = "  (required)"
			}
			fmt.Fprintf(o, "  --%s %s%s\n", p.Name, p.Type, required)
			fmt.Fprintf(o, "      %s\n", p.Description)
			if p.Example != "" {
				fmt.Fprintf(o, "      e.g. --%s %s\n", p.Name, p.Example)
			}
		}
		return nil
	}
	return &client.Error{
		Status:  404,
		Message: fmt.Sprintf("No action '%s' on %s.", action[0], feature),
		Hint:    fmt.Sprintf("ascli capability %s lists what it does have.", feature),
	}
}

func unknownCommand(command string, c *client.Client) error {
	// Before giving up: does a Platform Feature go by this name?
	if docs, err := c.Docs(context.Background()); err == nil {
		for _, capability := range docs.Capabilities {
			if strings.EqualFold(capability.ID, command) ||
				strings.EqualFold(cmds.CommandName(capability.ID), command) {
				return &client.Error{
					Status:  400,
					Message: fmt.Sprintf("'%s' is a Platform Feature, not an action.", command),
					Hint:    fmt.Sprintf("Try: ascli capability %s", capability.ID),
				}
			}
		}
	}
	return &client.Error{
		Status:  404,
		Message: fmt.Sprintf("No such command: %s", command),
		Hint:    "ascli capabilities lists every Platform Feature. ascli help explains the rest.",
	}
}

func usageError(message string) error {
	return &client.Error{Status: 400, Message: message, Hint: "ascli help explains the commands."}
}

func wantsHelp(args []string) bool {
	for _, arg := range args {
		if arg == "--help" || arg == "-h" {
			return true
		}
	}
	return false
}

// parseFlags turns --name value and --name=value into a map.
//
// Only long flags with values are collected. Unknown flags are left for the platform's parameter
// check to reject, because the CLI's table is generated and the platform's is authoritative.
func parseFlags(args []string) (map[string]string, error) {
	flags := map[string]string{}
	for i := 0; i < len(args); i++ {
		arg := args[i]
		if !strings.HasPrefix(arg, "--") {
			continue
		}
		name := strings.TrimPrefix(arg, "--")
		if name == "" {
			continue
		}

		if equals := strings.Index(name, "="); equals >= 0 {
			flags[name[:equals]] = name[equals+1:]
			continue
		}

		// A flag with no value is a boolean: present means true. That is what makes
		// `--include-system` work for a documented boolean.
		if i+1 < len(args) && !strings.HasPrefix(args[i+1], "--") {
			flags[name] = args[i+1]
			i++
			continue
		}
		flags[name] = "true"
	}
	return flags, nil
}

func boolFlag(args []string, name string) (bool, error) {
	for i := 0; i < len(args); i++ {
		if args[i] != name {
			continue
		}
		if i+1 < len(args) && !strings.HasPrefix(args[i+1], "--") {
			return args[i+1] != "false" && args[i+1] != "0", nil
		}
		return true, nil
	}
	return false, nil
}

func outputFlag(args []string) (string, error) {
	destination, ok, err := outputFlagOK(args)
	if err != nil {
		return "", err
	}
	if !ok {
		return "", usageError("where should the file go? Use -o <path>, or -o - for stdout.")
	}
	return destination, nil
}

func outputFlagOK(args []string) (string, bool, error) {
	for i := 0; i < len(args); i++ {
		if args[i] == "-o" || args[i] == "--output" || args[i] == "--out" {
			if i+1 >= len(args) {
				return "", false, usageError("-o needs a path, or - for stdout.")
			}
			return args[i+1], true, nil
		}
		for _, prefix := range []string{"--output=", "--out="} {
			if strings.HasPrefix(args[i], prefix) {
				return strings.TrimPrefix(args[i], prefix), true, nil
			}
		}
	}
	return "", false, nil
}

func isTTY() bool {
	info, err := os.Stdout.Stat()
	if err != nil {
		return false
	}
	return info.Mode()&os.ModeCharDevice != 0
}

// sortedKeys is here so help output is stable between runs; map order is not.
func sortedKeys(m map[string]bool) []string {
	keys := make([]string, 0, len(m))
	for k := range m {
		keys = append(keys, k)
	}
	sort.Strings(keys)
	return keys
}
