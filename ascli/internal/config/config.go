// Package config holds which platforms ascli knows about and how it proves who it is.
//
// Several devices, each with its own URL and its own token. The tokens are control of the
// phones: with one, anything that can reach the port can run hardware, read notifications and
// post messages. So the file is written only for the user to read, no token is ever printed in
// full by anything that was not explicitly asked to reveal it, and none is ever a positional
// argument where it would land in shell history.
package config

import (
	"encoding/json"
	"fmt"
	"os"
	"path/filepath"
	"runtime"
	"sort"
	"strings"
)

// ImplicitDevice is the name a platform is saved under when none was given.
//
// Not "unnamed": every saved platform has a name, because a device you have to identify by URL
// every time is the problem this file exists to solve. The implicit name means one phone needs no
// ceremony, and the moment there are two you have to say which.
const ImplicitDevice = "default"

// Device is one platform ascli can talk to.
type Device struct {
	BaseURL string `json:"baseUrl"`
	Token   string `json:"token,omitempty"`
	// SkipTLSVerify exists for the day the platform serves HTTPS with a self-signed certificate,
	// so getting in does not require us to ship a trust store. Off by default, never implicitly on,
	// and recorded per device because one platform may be behind a certificate the others are not.
	SkipTLSVerify bool `json:"skipTlsVerify,omitempty"`
}

// Redacted is the token as it may safely be shown: enough to recognise, not enough to use.
func (d Device) Redacted() string {
	if d.Token == "" {
		return ""
	}
	if len(d.Token) <= 8 {
		return strings.Repeat("*", len(d.Token))
	}
	return d.Token[:6] + "…" + d.Token[len(d.Token)-4:]
}

// Config is everything ascli remembers.
type Config struct {
	// Devices is keyed by the name given to `ascli connect`.
	Devices map[string]*Device `json:"devices,omitempty"`
	// Default is used when --device was not given. Empty means "the only device, if there is
	// exactly one", which is the single-phone case needing no configuration at all.
	Default string `json:"default,omitempty"`

	// Written by versions before devices existed, read once and folded into Devices. Kept so an
	// upgrade neither loses a working setup nor makes the reader delete the file by hand.
	BaseURL string `json:"baseUrl,omitempty"`
	Token   string `json:"token,omitempty"`
}

// Path is the config file's location, following each platform's own convention rather than
// inventing one directory layout for everybody.
func Path() string {
	if custom := os.Getenv("ASCLI_CONFIG"); custom != "" {
		return custom
	}
	if runtime.GOOS == "windows" {
		if appData := os.Getenv("APPDATA"); appData != "" {
			return filepath.Join(appData, "ascli", "config.json")
		}
	}
	if xdg := os.Getenv("XDG_CONFIG_HOME"); xdg != "" {
		return filepath.Join(xdg, "ascli", "config.json")
	}
	home, err := os.UserHomeDir()
	if err != nil {
		return filepath.Join(".", ".ascli.json")
	}
	return filepath.Join(home, ".config", "ascli", "config.json")
}

// Load reads the config, treating a missing file as an empty one.
//
// A corrupt file is an error rather than something to silently overwrite: losing the URLs means
// reconnecting, and losing the tokens means rotating them and telling every script that used them.
func Load() (*Config, error) {
	raw, err := os.ReadFile(Path())
	if os.IsNotExist(err) {
		return &Config{Devices: map[string]*Device{}}, nil
	}
	if err != nil {
		return nil, fmt.Errorf("cannot read %s: %w", Path(), err)
	}
	cfg := &Config{}
	if err := json.Unmarshal(raw, cfg); err != nil {
		return nil, fmt.Errorf("%s is not valid JSON: %w", Path(), err)
	}
	if cfg.Devices == nil {
		cfg.Devices = map[string]*Device{}
	}
	cfg.migrate()
	return cfg, nil
}

// migrate folds a pre-devices config into the devices map.
//
// One phone, one saved setup, and nothing to make the user do: it becomes the implicit device and
// everything that worked before still works.
func (c *Config) migrate() {
	if c.BaseURL == "" {
		return
	}
	if _, already := c.Devices[ImplicitDevice]; !already {
		c.Devices[ImplicitDevice] = &Device{BaseURL: c.BaseURL, Token: c.Token}
		if c.Default == "" {
			c.Default = ImplicitDevice
		}
	}
	c.BaseURL = ""
	c.Token = ""
}

// Save writes the config, and keeps it to the user.
//
// The directory is only restricted when this call created it: an existing one belongs to the user,
// or to the system as with /tmp when ASCLI_CONFIG points straight into it, and tightening
// something this tool does not own would be both wrong and not permitted.
func (c *Config) Save() error {
	path := Path()
	dir := filepath.Dir(path)

	_, statErr := os.Stat(dir)
	if os.IsNotExist(statErr) {
		if err := os.MkdirAll(dir, 0o700); err != nil {
			return fmt.Errorf("cannot create %s: %w", dir, err)
		}
		if err := os.Chmod(dir, 0o700); err != nil {
			return fmt.Errorf("cannot restrict %s to this user: %w", dir, err)
		}
	}

	body, err := json.MarshalIndent(c, "", "  ")
	if err != nil {
		return err
	}
	body = append(body, '\n')

	// Written to a temporary file and renamed, so an interrupted write cannot leave a truncated
	// config that fails to parse next time.
	tmp := path + ".tmp"
	if err := os.WriteFile(tmp, body, 0o600); err != nil {
		return fmt.Errorf("cannot write %s: %w", tmp, err)
	}
	// WriteFile only applies the mode when it creates the file, and a leftover temporary from an
	// earlier run may already exist — so permissions are set outright. Every token is in this file
	// now, one per device, which makes it worth being certain about.
	if err := os.Chmod(tmp, 0o600); err != nil {
		_ = os.Remove(tmp)
		return fmt.Errorf("cannot restrict %s to this user: %w", tmp, err)
	}
	if err := os.Rename(tmp, path); err != nil {
		_ = os.Remove(tmp)
		return fmt.Errorf("cannot save %s: %w", path, err)
	}
	return nil
}

// Resolve works out which device a command is about.
//
// Three rules, in order: what was asked for, then the configured default, then the only device if
// there is exactly one. Never a guess among several — with two phones connected, running a command
// against the wrong one is worse than being asked to say which.
func (c *Config) Resolve(name string) (deviceName string, device *Device, err error) {
	if name != "" {
		found, ok := c.Devices[name]
		if !ok {
			return "", nil, &UnknownDeviceError{Name: name, Known: c.Names()}
		}
		return name, found, nil
	}

	if c.Default != "" {
		if found, ok := c.Devices[c.Default]; ok {
			return c.Default, found, nil
		}
	}

	if len(c.Devices) == 1 {
		for only, found := range c.Devices {
			return only, found, nil
		}
	}

	return "", nil, &UnknownDeviceError{Name: "", Known: c.Names()}
}

// UnknownDeviceError is "no such device", or "several and you did not say which".
//
// Its own type because the two cases read differently to a user even though both are a naming
// mistake: one is a typo, the other is an omission.
type UnknownDeviceError struct {
	Name  string
	Known []string
}

func (e *UnknownDeviceError) Error() string {
	if len(e.Known) == 0 {
		return "No device is connected."
	}
	if e.Name == "" {
		return fmt.Sprintf("Several devices are connected (%s) and none was named.",
			strings.Join(e.Known, ", "))
	}
	return fmt.Sprintf("No device called '%s'.", e.Name)
}

// Hint is what to do about it, written out rather than left to the reader to work out.
func (e *UnknownDeviceError) Hint() string {
	if len(e.Known) == 0 {
		return "Connect one:  ascli connect <name> <url> --token <token>"
	}
	return fmt.Sprintf("Name one with --device <name>. Connected: %s\n"+
		"Or make one the default:  ascli devices default <name>",
		strings.Join(e.Known, ", "))
}

// ExitCode is how the process should end when this is what went wrong.
func (e *UnknownDeviceError) ExitCode() int { return 6 }

// Names lists the saved devices, sorted, so output is stable between runs.
func (c *Config) Names() []string {
	names := make([]string, 0, len(c.Devices))
	for name := range c.Devices {
		names = append(names, name)
	}
	sort.Strings(names)
	return names
}

// Put saves a device under a name, replacing whatever was there.
//
// Replacing is silent about nothing: the caller is expected to have checked. Overwriting a device's
// token is the normal way to rotate it, and refusing would make rotation impossible.
func (c *Config) Put(name string, device Device) {
	if c.Devices == nil {
		c.Devices = map[string]*Device{}
	}
	stored := device
	c.Devices[name] = &stored
	if c.Default == "" {
		c.Default = name
	}
}

// Remove forgets a device, and its token with it.
func (c *Config) Remove(name string) bool {
	if _, ok := c.Devices[name]; !ok {
		return false
	}
	delete(c.Devices, name)
	if c.Default == name {
		c.Default = ""
	}
	return true
}

// Clear forgets one device's token without forgetting the device.
func (d *Device) Clear() { d.Token = "" }

// Redact replaces anything that looks like a token in a string bound for a log or an error.
//
// Used on messages we did not construct, where a platform response or a URL could echo a secret
// back at us and we have no control over what it says.
func Redact(s, token string) string {
	if token == "" {
		return s
	}
	return strings.ReplaceAll(s, token, "<redacted>")
}
