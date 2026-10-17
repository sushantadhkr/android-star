package cmds

import (
	"encoding/json"
	"fmt"
	"io"
	"os"
	"sort"
	"strconv"
	"strings"

	"github.com/sushantadhkr/android-star/ascli/internal/client"
)

// Output is how results reach the terminal. One place decides between prose and JSON so no command
// has to think about it.
type Output struct {
	JSON bool
	// Verbose adds the request line, which is how you see what was actually sent.
	Verbose bool
	Out     io.Writer
	Err     io.Writer
}

// NewOutput builds one from the global flags, detecting a pipe.
//
// Defaulting to JSON when stdout is not a terminal is what makes `ascli device-info get | jq` work
// without a flag, and it is the right default: a human running a command sees a terminal, and a
// script does not.
func NewOutput(jsonFlag, verbose bool) *Output {
	return &Output{
		JSON:    jsonFlag || !isTerminal(os.Stdout),
		Verbose: verbose,
		Out:     os.Stdout,
		Err:     os.Stderr,
	}
}

// Write makes an Output usable wherever an io.Writer is wanted, so `fmt.Fprintf(o, ...)` works
// without every call site reaching through to the field.
func (o *Output) Write(p []byte) (int, error) { return o.Out.Write(p) }

func isTerminal(f *os.File) bool {
	info, err := f.Stat()
	if err != nil {
		return false
	}
	return info.Mode()&os.ModeCharDevice != 0
}

// Result prints an action's reply.
//
// The platform's JSON is passed through unchanged when JSON was asked for. Reformatting it would
// mean this tool decides what matters, and a field it has never heard of would quietly vanish on
// its way to whoever is reading.
func (o *Output) Result(raw []byte) error {
	if o.JSON {
		return pretty(raw, o.Out)
	}

	var fields map[string]any
	if err := json.Unmarshal(raw, &fields); err != nil {
		// Not an object — an array or a bare value. Shown as it is rather than reformatted.
		return pretty(raw, o.Out)
	}

	// Nested values are summarised one level: enough to see what came back, without turning a
	// terminal into a JSON viewer.
	single := true
	for _, key := range sortedKeys(fields) {
		value := fields[key]
		if nested, ok := value.(map[string]any); ok {
			value = summarise(nested)
		} else if list, ok := value.([]any); ok {
			value = summariseList(list)
		}
		fmt.Fprintf(o.Out, "  %-22s %v\n", key+":", value)
	}
	if single && len(fields) == 0 {
		fmt.Fprintln(o.Out, "  (empty)")
	}
	return nil
}

func summarise(m map[string]any) string {
	keys := sortedKeys(m)
	parts := make([]string, 0, len(keys))
	for _, k := range keys {
		if len(parts) == 3 {
			parts = append(parts, fmt.Sprintf("+%d more", len(keys)-3))
			break
		}
		parts = append(parts, fmt.Sprintf("%s=%v", k, m[k]))
	}
	return "{" + strings.Join(parts, ", ") + "}"
}

func summariseList(list []any) string {
	if len(list) == 0 {
		return "[]"
	}
	if len(list) > 1 {
		return fmt.Sprintf("%d items, first: %v", len(list), list[0])
	}
	return fmt.Sprintf("1 item: %v", list[0])
}

func sortedKeys(m map[string]any) []string {
	keys := make([]string, 0, len(m))
	for k := range m {
		keys = append(keys, k)
	}
	sort.Strings(keys)
	return keys
}

// pretty re-indents JSON, falling back to the raw bytes when it is not JSON at all.
func pretty(raw []byte, w io.Writer) error {
	var value any
	if err := json.Unmarshal(raw, &value); err != nil {
		_, err := w.Write(raw)
		return err
	}
	encoded, err := json.MarshalIndent(value, "", "  ")
	if err != nil {
		_, err := w.Write(raw)
		return err
	}
	_, err = fmt.Fprintln(w, string(encoded))
	return err
}

// Table prints aligned columns.
func (o *Output) Table(headers []string, rows [][]string) {
	if o.JSON {
		objects := make([]map[string]string, 0, len(rows))
		for _, row := range rows {
			obj := map[string]string{}
			for i, h := range headers {
				if i < len(row) {
					obj[strings.ToLower(strings.ReplaceAll(h, " ", ""))] = row[i]
				}
			}
			objects = append(objects, obj)
		}
		encoded, err := json.MarshalIndent(objects, "", "  ")
		if err == nil {
			fmt.Fprintln(o.Out, string(encoded))
		}
		return
	}

	widths := make([]int, len(headers))
	for i, h := range headers {
		widths[i] = len(h)
	}
	for _, row := range rows {
		for i, cell := range row {
			if i < len(widths) && len(cell) > widths[i] {
				widths[i] = len(cell)
			}
		}
	}

	fmt.Fprintln(o.Out, "  "+strings.Join(padAll(headers, widths), "  "))
	fmt.Fprintln(o.Out, "  "+strings.Join(dashes(widths), "  "))
	for _, row := range rows {
		fmt.Fprintln(o.Out, "  "+strings.Join(padAll(row, widths), "  "))
	}
}

func padAll(cells []string, widths []int) []string {
	out := make([]string, len(cells))
	for i, c := range cells {
		if i < len(widths) {
			out[i] = fmt.Sprintf("%-*s", widths[i], c)
		} else {
			out[i] = c
		}
	}
	return out
}

func dashes(widths []int) []string {
	out := make([]string, len(widths))
	for i, w := range widths {
		out[i] = strings.Repeat("-", w)
	}
	return out
}

// Mark is how a value is shown when on or off. Spelled out rather than coloured so it survives
// being piped, and so a screen reader does not have to announce a colour.
func Mark(on bool) string {
	if on {
		return "on"
	}
	return "off"
}

// coerce turns a command-line string into the JSON type the platform documented for it.
//
// The platform states each parameter's type, so the value is converted to that rather than sent as
// a string and left for the phone to refuse. A number sent as "42" is not the same thing to the
// platform, and finding that out from a 400 would be a poor way to learn it.
func coerce(value string, docType string) (any, error) {
	switch docType {
	case "integer":
		n, err := strconv.ParseInt(value, 10, 64)
		if err != nil {
			return nil, fmt.Errorf("expected a whole number, got %q", value)
		}
		return n, nil
	case "number":
		f, err := strconv.ParseFloat(value, 64)
		if err != nil {
			return nil, fmt.Errorf("expected a number, got %q", value)
		}
		return f, nil
	case "boolean":
		b, err := strconv.ParseBool(value)
		if err != nil {
			return nil, fmt.Errorf("expected true or false, got %q", value)
		}
		return b, nil
	case "array", "object":
		var parsed any
		if err := json.Unmarshal([]byte(value), &parsed); err != nil {
			return nil, fmt.Errorf("expected valid JSON for a %s, got %q", docType, value)
		}
		return parsed, nil
	default:
		return value, nil
	}
}

// ExitWith prints an error the way a person needs to read it, and returns the code to leave with.
func ExitWith(o *Output, err error) int {
	if err == nil {
		return client.ExitOK
	}

	var apiErr *client.Error
	if asAPIError(err, &apiErr) {
		fmt.Fprintf(o.Err, "  %s\n", apiErr.Error())
		if len(apiErr.Available) > 0 {
			fmt.Fprintf(o.Err, "  available: %s\n", strings.Join(apiErr.Available, ", "))
		}
		return apiErr.ExitCode()
	}

	fmt.Fprintf(o.Err, "  %s\n", err.Error())
	return client.ExitNetwork
}

func asAPIError(err error, target **client.Error) bool {
	if e, ok := err.(*client.Error); ok {
		*target = e
		return true
	}
	return false
}
