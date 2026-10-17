# ascli

The command line client for an Android Star platform.

```sh
ascli connect http://<phone>:8899 --token <token>
ascli doctor
ascli camera capture -o shot.jpg
```

## Why it is generated

The platform describes itself at `/api/capabilities/docs` — every Platform Feature, every action,
every parameter, with types and examples. ascli reads that and generates its commands from it, so
all 115 actions are reachable without anyone maintaining a list of them.

Nothing here is hand-written per action. Change the platform, run `make generate`, and the CLI
follows. **See [PLATFORM-CHANGES.md](PLATFORM-CHANGES.md)** — that is the whole instruction.

Two layers, on purpose:

| | |
|---|---|
| `ascli camera capture --max-width 1280` | generated, typed, with completion and help |
| `ascli invoke CAMERA capture --maxWidth 1280` | universal, by platform name |

The fallback exists so an out-of-date CLI still works. If the platform gains an action this build
has never heard of, `ascli invoke` still reaches it rather than telling you to go and update.

## Install

```sh
go install github.com/sushantadhkr/android-star/ascli@latest
```

Needs Go 1.22+ and nothing else — no clone, no Gradle, no Android SDK. Run it from outside the
repository; inside another module Go refuses to install with a version suffix.

Pin a version when you want to be sure what you got:

```sh
go install github.com/sushantadhkr/android-star/ascli@v0.1.1
ascli --version          # reports which build this is
```

Where it lands is `$(go env GOPATH)/bin`, which is usually already on your PATH. If it is not:

```sh
export PATH="$(go env GOPATH)/bin:$PATH"
```

Or from a checkout:

```sh
make -C ascli build      # ./ascli/ascli
make -C ascli install    # onto your PATH
make -C ascli dist       # cross-compiled binaries for macOS, Linux and Windows
```

One static binary either way, with no runtime and no dependencies. The platform's documentation is
compiled into it, so `ascli capabilities` works before you have connected anything.

Releases are tagged `ascli/vX.Y.Z`; `go install …@latest` takes the highest one.

Shell completion:

```sh
ascli completion bash > /etc/bash_completion.d/ascli      # or zsh, fish, powershell
```

## Connecting

Name each phone once, then use the name:

```sh
ascli connect pixel  http://192.168.1.42:8899  --token <token>
ascli connect work  http://10.0.0.7:8899       --token <token>

ascli --device pixel camera capture -o shot.jpg
```

The token is on each phone in **Settings → API access token**. To keep it out of your shell history:

```sh
printf %s "$TOKEN" | ascli connect pixel http://192.168.1.42:8899
```

Saved to `~/.config/ascli/config.json` (or `$XDG_CONFIG_HOME`, or `%APPDATA%` on Windows) with
mode `600`, in a directory with mode `700`. One file holds every token, which is the reason that
mode is not negotiable.

`ASCLI_URL` and `ASCLI_TOKEN` override the file; `ASCLI_DEVICE` names the device.

### One phone or two

```sh
ascli connect http://192.168.1.42:8899 --token <token>    # no name: saved as "default"
ascli devices                                            # list, and reach each one
ascli devices default pixel                              # used when --device is absent
ascli devices remove work                                # forget it and its token
```

With one phone, commands need no `--device` at all. With two, **ascli refuses to guess** — running a
command against the wrong phone is worse than being asked to say which:

```
$ ascli camera capture
  Several devices are connected (pixel, work) and none was named.
    Name one with --device <name>. Connected: pixel, work
  Or make one the default:  ascli devices default <name>
```

`ascli doctor` checks every connected phone and says which one is the problem.

## Everyday use

```sh
ascli --device pixel capabilities     # every Platform Feature
ascli capability CAMERA               # one feature, every action, with examples
ascli platform status                 # the master switch and every feature's state
ascli platform disable CAMERA         # afterwards every action on it returns 403
```

Actions are named after the platform, with underscores and capitals folded into dashes:

```sh
ascli device-info get
ascli download-manager fetch --url https://example.com/clip.mp4
ascli download-manager status --id dl_m0abc_0 --watch
ascli notification post --title "Hi" --message "there"
ascli sharing share-text --text "look at this"
```

### Getting files back

Produced files are addressed by an id the platform minted, never by a path — so there is no string
you could craft to reach something else on the device.

```sh
ascli camera capture -o shot.jpg              # runs the action, then fetches the photo
ascli camera capture -o - | pbcopy            # stream to stdout
ascli pull CAMERA cap_m0abc_1 -o shot.jpg     # fetch one already taken
ascli camera captures                         # what there is to fetch
```

## Output and exit codes

Human-readable tables and key/value output by default. **JSON when stdout is not a terminal**, so
`ascli device-info get | jq .model` works without a flag; `--no-json` forces the readable form and
`--json` forces machine output.

| Code | Meaning |
|---|---|
| 0 | it worked |
| 1 | the command was used wrongly |
| 2 | the platform rejected the token, or a Platform Feature is switched off |
| 3 | the platform refused on purpose |
| 4 | the platform could not be reached |
| 5 | whatever was asked for does not exist |
| 6 | no such device, or several and none was named |

A refusal is never reported as success. A `403` means a feature is switched off, and says which one
and how to turn it back on.

## When something is wrong

```sh
ascli doctor
```

Separates the four things that otherwise look identical: no platform connected, no token, cannot
reach the phone, and **this CLI is out of date with the platform**.

## When the platform changes

```sh
make -C ascli generate     # regenerate the commands
make -C ascli check        # what CI runs
```

`check` fails the build when the generated commands no longer match the platform's docs.

## Development

```sh
make -C ascli check     # vet + drift check + tests
make -C ascli test      # tests alone
make -C ascli generate --from some-docs.json   # generate from a payload you supply
```

Tests need no phone. The client is exercised against a real HTTP server standing in for one, so
refusals, parameter typing, token redaction and file streaming are all checked against actual
bytes rather than mocked out.
