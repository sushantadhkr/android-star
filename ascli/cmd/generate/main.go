// Command generate rebuilds ascli's command table from the platform's own documentation.
//
// This is the whole reason the CLI stays correct. Every action the platform exposes becomes a
// command, its documented parameters become flags, and its summaries become the help text — all
// read from one source rather than typed by hand.
//
// Run it with no arguments to write the file. Run it with --check to fail instead, which is what
// CI does: a platform change that nobody carried through to the CLI becomes a build failure
// rather than a surprise for whoever runs it next.
package main

import (
	"bytes"
	"context"
	"encoding/json"
	"flag"
	"fmt"
	"os"
	"time"

	"github.com/sushantadhkr/android-star/ascli/internal/client"
	"github.com/sushantadhkr/android-star/ascli/internal/cmds"
	"github.com/sushantadhkr/android-star/ascli/internal/config"
)

func main() {
	check := flag.Bool("check", false, "report drift and exit non-zero instead of writing")
	from := flag.String("from", "", "a docs JSON file to read instead of the bundled fixture")
	live := flag.Bool("live", false, "read the docs from the connected platform rather than the fixture")
	flag.Parse()

	docs, source, err := load(*from, *live)
	if err != nil {
		fmt.Fprintf(os.Stderr, "ascli generate: %v\n", err)
		os.Exit(1)
	}

	rendered, err := cmds.Render(docs)
	if err != nil {
		fmt.Fprintf(os.Stderr, "ascli generate: %v\n", err)
		os.Exit(1)
	}

	commands := cmds.CommandsFrom(docs)
	actions := 0
	for _, capability := range docs.Capabilities {
		actions += len(capability.Actions)
	}

	if *check {
		onDisk, err := os.ReadFile(cmds.GeneratedPath())
		if err != nil {
			fmt.Fprintf(os.Stderr,
				"ascli generate: cannot read %s: %v\n  run 'make generate'\n",
				cmds.GeneratedPath(), err)
			os.Exit(1)
		}
		if bytes.Equal(bytes.TrimSpace(onDisk), bytes.TrimSpace([]byte(rendered))) {
			fmt.Printf("ascli is up to date: %d Platform Features, %d actions, %d commands (%s)\n",
				len(docs.Capabilities), actions, len(commands), source)
			return
		}

		drift := cmds.Compare(docs)
		fmt.Fprintf(os.Stderr, "ascli's commands do not match the platform (%s).\n", source)
		for _, name := range drift.Missing {
			fmt.Fprintf(os.Stderr, "  + %s          the platform has this, the CLI does not\n", name)
		}
		for _, name := range drift.Extra {
			fmt.Fprintf(os.Stderr, "  - %s          the CLI has this, the platform does not\n", name)
		}
		for _, changed := range drift.Changed {
			fmt.Fprintf(os.Stderr, "  ~ %s          %s\n", changed.Command, changed.Reason)
			for _, missing := range changed.Missing {
				fmt.Fprintf(os.Stderr, "      + --%s\n", missing)
			}
			for _, extra := range changed.Extra {
				fmt.Fprintf(os.Stderr, "      - --%s\n", extra)
			}
		}
		fmt.Fprintf(os.Stderr, "\n  Fix it with:\n    make -C ascli generate\n")
		os.Exit(1)
	}

	if err := os.WriteFile(cmds.GeneratedPath(), []byte(rendered), 0o644); err != nil {
		fmt.Fprintf(os.Stderr, "ascli generate: cannot write %s: %v\n", cmds.GeneratedPath(), err)
		os.Exit(1)
	}

	fmt.Printf("wrote %s: %d Platform Features, %d actions, %d commands (%s)\n",
		cmds.GeneratedPath(), len(docs.Capabilities), actions, len(commands), source)
}

// load finds the docs payload to generate from, in the order the flags ask for.
//
// An explicit file wins over a live platform, which wins over the committed fixture — so the
// generator can be pointed at a colleague's payload, at the phone on the desk, or at the file in
// the repository, without changing how it is invoked otherwise.
func load(from string, useLive bool) (*client.Docs, string, error) {
	if from != "" {
		raw, err := os.ReadFile(from)
		if err != nil {
			return nil, "", fmt.Errorf("cannot read %s: %w", from, err)
		}
		docs := &client.Docs{}
		if err := json.Unmarshal(raw, docs); err != nil {
			return nil, "", fmt.Errorf("%s is not valid docs JSON: %w", from, err)
		}
		if len(docs.Capabilities) == 0 {
			return nil, "", fmt.Errorf("%s lists no capabilities", from)
		}
		return docs, from, nil
	}

	if useLive {
		cfg, err := config.Load()
		if err != nil {
			return nil, "", err
		}
		if cfg.BaseURL == "" {
			return nil, "", fmt.Errorf("--live was asked for but no platform is connected. " +
				"Run 'ascli connect <url> --token <token>' first, or drop --live to use the fixture.")
		}
		ctx, cancel := context.WithTimeout(context.Background(), 30*time.Second)
		defer cancel()

		c := client.New(cfg.BaseURL, cfg.Token, cfg.SkipTLSVerify)
		docs, err := c.Docs(ctx)
		if err != nil {
			return nil, "", err
		}
		return docs, fmt.Sprintf("%s (live)", cfg.BaseURL), nil
	}

	docs, err := cmds.LoadFixture()
	if err != nil {
		return nil, "", err
	}
	path, _ := cmds.FindFixture()
	return docs, path, nil
}
