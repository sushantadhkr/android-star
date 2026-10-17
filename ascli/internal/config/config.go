// Package config holds where ascli points and how it proves who it is.
//
// The token in here is the same one the phone's Settings screen shows, and it is control of the
// device: with it, anything that can reach the port can run hardware, read notifications and post
// messages. So it is written to a file only the user can read, never printed in full by anything
// that is not explicitly asked to reveal it, and kept out of shell history by never being a
// positional argument.
package config

import (
	"encoding/json"
	"fmt"
	"os"
	"path/filepath"
	"runtime"
	"strings"
)

// Config is the whole of ascli's remembered state.
type Config struct {
	BaseURL string `json:"baseUrl,omitempty"`
	Token   string `json:"token,omitempty"`
	// SkipTLSVerify is here so the day the platform serves HTTPS with a self-signed certificate,
	// a user can get in without us shipping a trust store. It is off by default and never
	// implicitly on.
	SkipTLSVerify bool `json:"skipTlsVerify,omitempty"`
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
// A corrupt file is an error rather than something to silently overwrite: losing the base URL means
// reconnecting, and losing the token means rotating it and telling every script that used it.
func Load() (*Config, error) {
	raw, err := os.ReadFile(Path())
	if os.IsNotExist(err) {
		return &Config{}, nil
	}
	if err != nil {
		return nil, fmt.Errorf("cannot read %s: %w", Path(), err)
	}
	cfg := &Config{}
	if err := json.Unmarshal(raw, cfg); err != nil {
		return nil, fmt.Errorf("%s is not valid JSON: %w", Path(), err)
	}
	return cfg, nil
}

// Save writes the config, and keeps it to the user.
//
// The directory is 0700 and the file 0600 rather than the umask default, because a token that a
// neighbouring account can read is not a token.
func (c *Config) Save() error {
	path := Path()
	dir := filepath.Dir(path)

	// Only a directory this call created is ours to restrict. An existing one belongs to the user
	// — or to the system, as with /tmp when ASCLI_CONFIG points straight into it — and silently
	// tightening its permissions would be both wrong and something we are not permitted to do.
	_, statErr := os.Stat(dir)
	createdHere := os.IsNotExist(statErr)

	if createdHere {
		if err := os.MkdirAll(dir, 0o700); err != nil {
			return fmt.Errorf("cannot create %s: %w", dir, err)
		}
		// MkdirAll applies the mode to directories it makes, but a parent that already existed keeps
		// its own, so the leaf is set explicitly rather than assumed.
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
	// earlier run may already exist — so the file's permissions are set outright. This one is always
	// ours, unlike the directory above.
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

// Clear forgets the token, which is what someone reaches for when they think it has leaked.
func (c *Config) Clear() {
	c.Token = ""
}

// Redacted is the token as it may safely be shown: enough to recognise, not enough to use.
func (c *Config) Redacted() string {
	if c.Token == "" {
		return ""
	}
	if len(c.Token) <= 8 {
		return strings.Repeat("*", len(c.Token))
	}
	return c.Token[:6] + "…" + c.Token[len(c.Token)-4:]
}

// Redact replaces anything that looks like the token in a string bound for a log or an error.
//
// Used on messages we did not construct, where a platform response or a URL could echo a secret
// back at us and we have no control over what it says.
func Redact(s, token string) string {
	if token == "" {
		return s
	}
	return strings.ReplaceAll(s, token, "<redacted>")
}
