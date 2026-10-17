package cmds

import (
	"context"
	"fmt"
	"strings"

	"github.com/sushantadhkr/android-star/ascli/internal/client"
)

// Platform turns the master switch and each Platform Feature on and off. `ascli platform status`,
// `on`, `off`, `enable` and `disable` all run through here.
type Platform struct {
	Client *client.Client
	Out    *Output
}

// Status reports the master switch and every Platform Feature.
func (p *Platform) Status(ctx context.Context) error {
	cat, err := p.Client.Catalogue(ctx)
	if err != nil {
		return err
	}

	if p.Out.JSON {
		return pretty(mustJSON(cat), p.Out)
	}

	rows := make([][]string, 0, len(cat.Capabilities))
	for _, entry := range cat.Capabilities {
		status := entry.Status
		if status == "" {
			status = "—"
		}
		rows = append(rows, []string{entry.ID, Mark(entry.Enabled), status})
	}

	p.Out.Table([]string{"FEATURE", "STATE", "STATUS"}, rows)
	fmt.Fprintf(p.Out, "\n  master switch: %s\n", Mark(cat.MasterEnabled))
	return nil
}

// SetMaster flips the master switch.
func (p *Platform) SetMaster(ctx context.Context, enabled bool) error {
	if _, err := p.Client.Toggle(ctx, "", enabled); err != nil {
		return err
	}
	if !p.Out.JSON {
		fmt.Fprintf(p.Out, "  master switch %s\n", Mark(enabled))
		if !enabled {
			fmt.Fprintf(p.Out, "  nothing on the platform can be reached until it is back on\n")
		}
	}
	return nil
}

// SetFeature flips one Platform Feature.
func (p *Platform) SetFeature(ctx context.Context, feature string, enabled bool) error {
	if _, err := p.Client.Toggle(ctx, feature, enabled); err != nil {
		return err
	}
	if p.Out.JSON {
		return nil
	}
	fmt.Fprintf(p.Out, "  %s %s\n", feature, Mark(enabled))
	if enabled {
		fmt.Fprintf(p.Out, "  reachable as:  ascli %s <action>\n", CommandName(feature))
	} else {
		fmt.Fprintf(p.Out, "  every action on it now returns 403, whatever asks\n")
	}
	return nil
}

// Capabilities lists every Platform Feature, read from the platform rather than remembered.
func Capabilities(ctx context.Context, c *client.Client, o *Output, filter string) error {
	docs, source, err := DocsFromLiveOrFixture(ctx, c)
	if err != nil {
		return err
	}

	if o.JSON {
		return pretty(mustJSON(docs), o)
	}

	rows := make([][]string, 0, len(docs.Capabilities))
	for _, capability := range docs.Capabilities {
		if filter != "" && !strings.Contains(strings.ToLower(capability.ID), strings.ToLower(filter)) {
			continue
		}
		rows = append(rows, []string{
			capability.ID,
			CommandName(capability.ID),
			capability.GroupName,
			fmt.Sprintf("%d", len(capability.Actions)),
		})
	}

	o.Table([]string{"FEATURE", "COMMAND", "GROUP", "ACTIONS"}, rows)
	if source == "bundled" {
		fmt.Fprintf(o.Out, "\n  read from the bundled docs — no platform is connected\n")
		fmt.Fprintf(o.Out, "  connect one with:  ascli connect <url> --token <token>\n")
	}
	return nil
}

// Capability shows one Platform Feature in full: every action, its parameters, and a worked
// example of each. This is the CLI's own `--help`, read live off the platform.
func Capability(ctx context.Context, c *client.Client, o *Output, id string) error {
	docs, _, err := DocsFromLiveOrFixture(ctx, c)
	if err != nil {
		return err
	}

	for _, capability := range docs.Capabilities {
		if !strings.EqualFold(capability.ID, id) && !strings.EqualFold(CommandName(capability.ID), id) {
			continue
		}

		if o.JSON {
			return pretty(mustJSON(capability), o)
		}

		fmt.Fprintf(o.Out, "  %s — %s\n", capability.ID, capability.DisplayName)
		fmt.Fprintf(o.Out, "  %s\n\n", capability.Description)
		if len(capability.RequiredPermissions) > 0 {
			fmt.Fprintf(o.Out, "  needs: %s\n\n", strings.Join(capability.RequiredPermissions, ", "))
		}

		for _, action := range capability.Actions {
			consent := ""
			if action.RequiresUserConsent {
				consent = "  [asks the user on the phone]"
			}
			fmt.Fprintf(o.Out, "  ascli %s %s%s\n", CommandName(capability.ID), CommandName(action.Name), consent)
			fmt.Fprintf(o.Out, "      %s\n", action.Summary)
			for _, param := range action.Params {
				required := ""
				if param.Required {
					required = " (required)"
				}
				example := ""
				if param.Example != "" {
					example = fmt.Sprintf("  e.g. %s", param.Example)
				}
				fmt.Fprintf(o.Out, "      --%s %s%s%s\n", param.Name, param.Type, required, example)
				fmt.Fprintf(o.Out, "          %s\n", param.Description)
			}
			if action.ExampleRequest != "" {
				fmt.Fprintf(o.Out, "      request:  %s\n", action.ExampleRequest)
			}
			if action.ExampleResponse != "" {
				fmt.Fprintf(o.Out, "      replies:  %s\n", truncate(action.ExampleResponse, 170))
			}
			fmt.Fprintln(o)
		}
		return nil
	}

	return &client.Error{
		Status:  404,
		Message: fmt.Sprintf("No Platform Feature called '%s'.", id),
		Hint:    "Run 'ascli capabilities' to list them all.",
	}
}

func truncate(s string, max int) string {
	if len(s) <= max {
		return s
	}
	return s[:max] + "…"
}

// commandNameFor turns DEVICE_INFO into device-info and shareText into share-text.
//
// The rule, precisely: a separator in the source becomes a dash; a capital folds to lower case;
// and a dash is inserted before a capital only where a new word actually starts, which means the
// character before it was a lower-case letter or a digit. A run of capitals gets no dashes at all,
// so DEVICE_ACTIONS is "device-actions" and not "d-e-v-i-c-e-a-c-t-i-o-n-s".
func commandNameFor(id string) string {
	var b strings.Builder
	previous := rune(0)

	for _, r := range id {
		switch {
		case r == '_' || r == ' ' || r == '.' || r == '-':
			if b.Len() > 0 && !strings.HasSuffix(b.String(), "-") {
				b.WriteByte('-')
			}
		case r >= 'A' && r <= 'Z':
			// A dash only at a genuine boundary: lower-case or digit before, or the start.
			atWordStart := b.Len() == 0 ||
				(previous >= 'a' && previous <= 'z') ||
				(previous >= '0' && previous <= '9')
			if atWordStart && !strings.HasSuffix(b.String(), "-") {
				b.WriteByte('-')
			}
			b.WriteRune(r - 'A' + 'a')
		default:
			b.WriteRune(r)
		}
		previous = r
	}
	return strings.Trim(b.String(), "-")
}

// CommandName exposes the naming rule so the generated commands use exactly the same one.
func CommandName(id string) string { return commandNameFor(id) }
