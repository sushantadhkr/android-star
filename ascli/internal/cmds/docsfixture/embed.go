// Package docsfixture carries the platform's own documentation inside the binary.
//
// Without it, an installed ascli can only describe the API from a checkout: the fixture was found
// by walking up the filesystem, which works in the repository and nowhere else. So `ascli
// capabilities` — the first thing anyone runs — failed for anyone who installed it rather than
// cloned it, which is most people.
//
// The go tool refuses to embed anything under a directory named testdata, so the copy lives here
// and is kept in step by `make check`.
package docsfixture

import _ "embed"

// Docs is a captured /api/capabilities/docs payload.
//
//go:embed docs.json
var Docs []byte
