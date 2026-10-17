# ascli — the Android Star command line client.
#
# Generated code lives in internal/cmds/commands_gen.go and is built from the platform's own
# documentation. Regenerate it whenever the platform changes; `make check` fails if you did not.

VERSION ?= $(shell git describe --tags --always --dirty 2>/dev/null || echo dev)
LDFLAGS := -s -w -X main.version=$(VERSION)

PLATFORMS := darwin/amd64 darwin/arm64 linux/amd64 linux/arm64 windows/amd64 windows/arm64

.PHONY: all build install test check generate generate-check fmt vet clean dist help

all: check build

## build — a binary for this machine
build:
	go build -trimpath -ldflags '$(LDFLAGS)' -o ascli .

## install — put ascli on your PATH
install:
	go install -trimpath -ldflags '$(LDFLAGS)' .

## test — everything, including whether the CLI still matches the platform
test:
	go test ./... -count=1

## generate — rebuild the command table from the platform's documentation
generate:
	go run ./cmd/generate

## generate-check — fail if the platform has moved and generate was not run
generate-check:
	go run ./cmd/generate --check

## check — what CI runs
check: vet generate-check test
	@node ../tools/check-secrets.js
	@echo "ascli is up to date with the platform"

## fmt — gofmt everything
fmt:
	gofmt -l -w .

## vet — the standard static checks
vet:
	go vet ./...

## dist — cross-compiled binaries for every platform, into dist/
dist:
	@mkdir -p dist
	@for platform in $(PLATFORMS); do \
		os=$${platform%/*}; arch=$${platform#*/}; \
		ext=""; [ "$$os" = "windows" ] && ext=".exe"; \
		echo "  $$os/$$arch"; \
		GOOS=$$os GOARCH=$$arch go build -trimpath -ldflags '$(LDFLAGS)' \
			-o dist/ascli_$${os}_$${arch}$$ext . || exit 1; \
	done
	@ls -la dist/

## clean — build output, but not the generated table
clean:
	rm -rf dist ascli
	go clean

## help — what each target does
help:
	@grep -E '^## ' $(MAKEFILE_LIST) | sed 's/## /  /'
