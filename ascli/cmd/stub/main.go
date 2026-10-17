// Command stub serves a fake Android Star platform, so the CLI can be exercised end to end without
// a phone attached.
//
// It is a test fixture, not part of the product: it answers the same routes with the same shapes,
// including the refusals, because the parts of ascli worth checking are the ones that happen when
// the platform says no.
//
//	go run ./cmd/stub -port 8899 &
//	export ASCLI_URL=http://127.0.0.1:8899 ASCLI_TOKEN=stub-token
//	ascli doctor
package main

import (
	"encoding/json"
	"flag"
	"fmt"
	"io"
	"log"
	"net/http"
	"os"
	"path/filepath"
	"strings"
	"sync"
)

var token = flag.String("token", "stub-token", "the bearer token this stub accepts")
var port = flag.Int("port", 8899, "port to listen on")

// state the stub keeps between calls, so a capture can be captured and then pulled.
var (
	mu        sync.Mutex
	captures  = map[string][]byte{}
	downloads = map[string]string{}
	nextID    int
)

func main() {
	flag.Parse()

	mux := http.NewServeMux()
	mux.HandleFunc("/", func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path == "/" {
			// Public, like the real platform's console: a browser loading the page has not sent a
			// bearer token yet, and everything under /api/ still does.
			w.Header().Set("Content-Type", "text/html")
			_, _ = w.Write([]byte("<html><body>Android Star console</body></html>"))
			return
		}
		writeJSON(w, 404, map[string]any{"error": "Not Found"})
	})

	mux.HandleFunc("/api/capabilities", requireToken(func(w http.ResponseWriter, r *http.Request) {
		writeJSON(w, 200, map[string]any{
			"masterEnabled": true,
			"capabilities": []map[string]any{
				{"id": "CAMERA", "enabled": true, "status": "ready", "group": "CAPTURE"},
				{"id": "DEVICE_INFO", "enabled": true, "status": "ready", "group": "DEVICE"},
				{"id": "FLASHLIGHT", "enabled": false, "status": "ready", "group": "DISPLAY"},
			},
		})
	}))

	mux.HandleFunc("/api/capabilities/docs", requireToken(func(w http.ResponseWriter, r *http.Request) {
		path, err := findDocs()
		if err != nil {
			writeJSON(w, 500, map[string]any{"error": err.Error()})
			return
		}
		body, err := os.ReadFile(path)
		if err != nil {
			writeJSON(w, 500, map[string]any{"error": err.Error()})
			return
		}
		w.Header().Set("Content-Type", "application/json")
		_, _ = w.Write(body)
	}))

	mux.HandleFunc("/api/capabilities/invoke", requireToken(func(w http.ResponseWriter, r *http.Request) {
		var payload map[string]any
		body, _ := io.ReadAll(r.Body)
		_ = json.Unmarshal(body, &payload)

		capability, _ := payload["capability"].(string)
		action, _ := payload["action"].(string)

		// A switched-off feature refuses, exactly as the real one does. This is the case ascli
		// must not turn into a success.
		if capability == "FLASHLIGHT" {
			writeJSON(w, 403, map[string]any{"error": "Capability 'FLASHLIGHT' is switched off"})
			return
		}

		switch capability + "." + action {
		case "CAMERA.capture":
			mu.Lock()
			nextID++
			id := fmt.Sprintf("cap_stub%d", nextID)
			captures[id] = []byte{0xFF, 0xD8, 0xFF, 0xE0} // a JPEG magic number, for realism
			mu.Unlock()
			writeJSON(w, 200, map[string]any{
				"id": id, "fileName": id + ".jpg", "bytes": len(captures[id]),
				"pullUrl": "/api/files/pull?capability=CAMERA&id=" + id,
			})

		case "CAMERA.captures":
			mu.Lock()
			list := []map[string]any{}
			for id := range captures {
				list = append(list, map[string]any{
					"id": id, "fileName": id + ".jpg", "bytes": len(captures[id]),
					"pullUrl": "/api/files/pull?capability=CAMERA&id=" + id,
				})
			}
			mu.Unlock()
			writeJSON(w, 200, map[string]any{"count": len(list), "captures": list})

		case "CAMERA.pull":
			id, _ := payload["id"].(string)
			mu.Lock()
			bytes, known := captures[id]
			mu.Unlock()
			if !known {
				writeJSON(w, 404, map[string]any{"error": "No capture with id '" + id + "'."})
				return
			}
			writeJSON(w, 200, map[string]any{
				"id": id, "fileName": id + ".jpg", "bytes": len(bytes),
				"contentType": "image/jpeg",
				"pullUrl":     "/api/files/pull?capability=CAMERA&id=" + id,
			})

		case "DEVICE_INFO.get":
			writeJSON(w, 200, map[string]any{
				"manufacturer": "Google", "model": "Pixel 7", "sdkInt": 36,
			})

		case "DOWNLOAD_MANAGER.fetch":
			url, _ := payload["url"].(string)
			mu.Lock()
			nextID++
			id := fmt.Sprintf("dl_stub%d", nextID)
			downloads[id] = url
			mu.Unlock()
			writeJSON(w, 200, map[string]any{"id": id, "status": "queued", "percent": 0})

		case "DOWNLOAD_MANAGER.status":
			id, _ := payload["id"].(string)
			mu.Lock()
			_, known := downloads[id]
			mu.Unlock()
			if !known {
				writeJSON(w, 404, map[string]any{"error": "No download with id '" + id + "'."})
				return
			}
			// Reports complete on the second poll, so --watch has something to watch.
			writeJSON(w, 200, map[string]any{
				"id": id, "status": "complete", "percent": 100, "bytesDownloaded": 4, "totalBytes": 4,
			})

		default:
			writeJSON(w, 400, map[string]any{
				"error":     "Unknown action '" + action + "' for " + capability,
				"available": []string{"cameras", "capture", "captures", "pull"},
				"docs":      "/api/capabilities/docs",
			})
		}
	}))

	mux.HandleFunc("/api/capabilities/toggle", requireToken(func(w http.ResponseWriter, r *http.Request) {
		var payload map[string]any
		body, _ := io.ReadAll(r.Body)
		_ = json.Unmarshal(body, &payload)
		enabled, _ := payload["enabled"].(bool)
		capability, _ := payload["capability"].(string)

		if capability == "" {
			writeJSON(w, 200, map[string]any{"masterEnabled": enabled})
			return
		}
		if capability == "NOT_REAL" {
			writeJSON(w, 404, map[string]any{"error": "No capability named '" + capability + "'"})
			return
		}
		writeJSON(w, 200, map[string]any{"capability": capability, "enabled": enabled})
	}))

	mux.HandleFunc("/api/files/pull", requireToken(func(w http.ResponseWriter, r *http.Request) {
		capability := r.URL.Query().Get("capability")
		id := r.URL.Query().Get("id")
		if capability == "" || id == "" {
			writeJSON(w, 400, map[string]any{"error": "Missing 'capability' or 'id'"})
			return
		}

		mu.Lock()
		defer mu.Unlock()
		switch capability {
		case "CAMERA":
			bytes, known := captures[id]
			if !known {
				writeJSON(w, 404, map[string]any{
					"error": "No file for id '" + id + "' in CAMERA",
				})
				return
			}
			w.Header().Set("Content-Type", "image/jpeg")
			w.Header().Set("Content-Disposition", `attachment; filename="`+id+`.jpg"`)
			w.Header().Set("Content-Length", fmt.Sprintf("%d", len(bytes)))
			_, _ = w.Write(bytes)
		case "DOWNLOAD_MANAGER":
			_, known := downloads[id]
			if !known {
				writeJSON(w, 404, map[string]any{
					"error": "No file for id '" + id + "' in DOWNLOAD_MANAGER",
				})
				return
			}
			w.Header().Set("Content-Type", "application/octet-stream")
			_, _ = w.Write([]byte("data"))
		default:
			writeJSON(w, 404, map[string]any{
				"error": "'" + capability + "' does not produce files that can be pulled",
			})
		}
	}))

	fmt.Fprintf(os.Stderr, "stub platform on http://127.0.0.1:%d (token %q)\n", *port, *token)
	log.Fatal(http.ListenAndServe(fmt.Sprintf("127.0.0.1:%d", *port), mux))
}

// findDocs locates the docs payload by walking up from the working directory, so the stub can be
// started from anywhere rather than only from inside the module.
func findDocs() (string, error) {
	candidates := []string{
		filepath.Join("ascli", "testdata", "docs.json"),
		filepath.Join("testdata", "docs.json"),
		filepath.Join("tools", "fixtures", "docs-sample.json"),
		filepath.Join("..", "tools", "fixtures", "docs-sample.json"),
	}

	dir, err := os.Getwd()
	if err != nil {
		return "", err
	}
	for {
		for _, candidate := range candidates {
			full := filepath.Join(dir, candidate)
			if _, err := os.Stat(full); err == nil {
				return full, nil
			}
		}
		parent := filepath.Dir(dir)
		if parent == dir {
			return "", fmt.Errorf("stub cannot find a docs payload (looked for %s)", strings.Join(candidates, ", "))
		}
		dir = parent
	}
}

func requireToken(next http.HandlerFunc) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		presented := r.Header.Get("Authorization")
		if presented != "Bearer "+*token {
			w.Header().Set("WWW-Authenticate", "Bearer")
			writeJSON(w, 401, map[string]any{
				"error": "Unauthorized",
				"hint":  "Send 'Authorization: Bearer <token>'",
			})
			return
		}
		next(w, r)
	}
}

func writeJSON(w http.ResponseWriter, status int, payload any) {
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(status)
	_ = json.NewEncoder(w).Encode(payload)
}
