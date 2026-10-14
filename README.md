# Android Personal Base Platform

A base Android app you **extend forever**. When you need something new — a frontend for your
backend, a webcam server, a stock management system — you build it as an **App** on this base
instead of starting over. The platform already provides capabilities, transport, storage, security
and a UI kit, so each App writes only what is genuinely its own.

Built with Kotlin, Jetpack Compose and Material 3. `minSdk 24`, `compileSdk 36`.

---

## Vocabulary — three terms, no others

This distinction governs everything. There is no "plugin" category and no fourth kind of thing.

```
PLATFORM APP          the whole application — the container
├── PLATFORM FEATURES   built directly on the platform · pinned · not removable
└── APPS                individual things you build · user-controlled
```

| Term | Means | Editable? |
|---|---|---|
| **Platform App** | The whole application: shell, navigation, page structure, core library. | **Frozen.** Features are never added here. |
| **Platform Feature** | A capability of the platform itself — the Capabilities API, the security lock. | Pinned. Toggle only, never removable. |
| **App** | A specific thing you build — a webcam server, a backend client, a stock management system. | Extensible forever. |

A **platform setting** and an **app setting** are different things and are never conflated.

### The One Rule

> **We never write code at the platform level.**
> The only code we add is: **extend a core capability to its full capability.**
> Apps then use those capabilities however they want.

If something seems to need platform code, the real move is to make a core capability complete, then
build the feature as an App on top of it. The shell is scaffolding, not a feature laboratory. This is
what keeps the base expandable without forking.

### Apps have no size limit

An App may be a single file or a very large system with its own database, many screens and
long-lived state. There is **one contract**, and it does not care how big the App is — the contract is
a boundary, not a template for how much code follows it.

---

## Two kinds of things, with opposite instance rules

This is the most important distinction in the system.

### Apps — one instance per App, gated by its toggle

An App is a feature with its own state and lifecycle. Switching off the webcam App must not affect a
notes App.

### Capabilities — one instance per platform, shared by every App

A capability is **the device itself** — the camera, the microphone, the notification manager. There
is one of each on a device, so there is one instance, and every App that needs it shares it. An App
never owns a private copy of a device service.

```
WRONG   App A → own notification manager + port
        App B → own notification manager + port      (duplicate services contending for one)

RIGHT   ┌─ NotificationManager  (single platform instance)
        │   ├─ channel for App A     ← App A posts here
        │   ├─ channel for App B     ← App B posts here
        │   └─ channel for App C     ← App C posts here
```

The **manager is the shared singleton; the channel is the per-App slot.** The same shape applies to
hardware: the camera capability manages the device's single camera; a webcam App *consumes* it, it
does not create a second camera.

---

## The pages

Apps are the top layer. Everything else sits beneath them.

| Page | Purpose | Rules |
|---|---|---|
| **Dashboard** | Cards and Actions contributed by Apps | **Starts empty and clean.** Only two kinds: **Card** and **Action**. The user hides and reorders them. |
| **Apps** | The list of all Apps | Search, filter, reorder. **Every App appears here.** Each carries a mandatory on/off toggle. |
| **Logs** | Platform-wide log stream | A **one-way sink.** Every App's logs land here. Hiding is not possible. |
| **Settings** | Platform and per-App configuration | Platform and App settings are distinct categories. Filter at the top. |

There is **no top-level Capabilities page** — capabilities are reached from Settings.

---

## Writing an App

Annotate a class. The registry is generated at build time, so **no shell file is ever edited**.

```kotlin
@PlatformAppDefinition(id = "my-app", name = "My App", order = 20)
class MyApp : PlatformApp {

    override val id = "my-app"
    override val name = "My App"
    override val icon = Icons.Default.WavingHand
    override val description = "A minimal App demonstrating the contract."
    override val version = "1.0"

    // State exists only while the user has this App switched on.
    private var greetings = 0

    // Declare what you contribute. Everything here is optional.
    override val contribution = AppContribution(
        dashboardCard   = { scope -> GreetingCard(scope) },
        dashboardActions = listOf(
            DashboardAction(id = "greet", label = "Say hello", onInvoke = { greetings++ })
        ),
        settingsSection = { scope -> /* your settings UI */ }
    )

    // Your own full screen, opened by tapping your card.
    @Composable
    override fun AppContent(scope: AppScope, onBack: () -> Unit) { /* … */ }
}
```

That is the whole integration. The App now appears on the Apps page with its toggle, its card
renders on the dashboard, and its logs flow to the Logs page.

### What an App may contribute

| Surface | What it provides | Where it appears |
|---|---|---|
| **Dashboard** | a Card, an Action, both, or nothing | Dashboard |
| **Settings** | a settings section, or nothing | Settings |
| **Own screen** | a full-screen experience | Opened by tapping its card |
| **Server routes** | network endpoints | Served to the network |
| **Logs** | any use of core logging | Always flows to the Logs page |

**Apps are never forced to use the dashboard or the settings.** Headless Apps are normal — sync,
relays, watchers — and simply have no card; they surface only on the Apps page and in Logs.

### Exposing a capability as a server

Implement `CapabilityServingApp` and your capability becomes a network service. The route is bound to
the App's live instance, so a stopped App serves nothing.

```kotlin
class WebcamApp : PlatformApp, CapabilityServingApp {
    override fun httpPayload(): String = """{"streaming":true,"clients":$clients}"""
    override val servePath: String get() = "camera"
}
```

### How registration works

```
one annotated App file
        │
        ├── build step generates the registry automatically
        │        (nobody hand-writes the list, so no platform code is touched)
        ▼
platform reads the generated registry at startup
        │
        ▼
user toggles the App ON → its single instance is created
```

The registry is generated rather than hand-maintained, so adding an App requires no edit to the
shell. Registration is **not** permission: a registered App still has its mandatory user toggle, and
capability access is still gated by the core authorization layer. Apps ship inside the application
and are compiled at build time — loading them dynamically at runtime is a separate, larger problem.

---

## App lifecycle and the safety toggle

Every App, whatever its size, carries a mandatory on/off toggle.

- The **user** holds sole control. Nothing can force an App on.
- The toggle appears on that App's entry in the **Apps** page.
- **Off → the App is inert.** No instance exists, nothing executes, and its state is cleared — a
  full teardown, as though the App were wiped.
- **On → the App's instance is created**, it runs, and it contributes what it declared.
- **The platform remembers the toggle state** and it survives restarts.
- When an App is off, **its dashboard card stays but reads as disabled**, preserving the layout the
  user arranged.

> Rationale: the user must always be able to completely stop any App on the device.

### One instance, many views

An App appearing in several places is a **single instance** viewed from several angles:

```
user toggles App ON → one instance is created
                         ├─ dashboard card       (a view onto it)
                         ├─ the App's own screen (a view onto it)
                         └─ settings section     (a view onto it)
```

Never two instances. The card and the full screen read the same live state — this is what makes the
abstraction real rather than nominal.

---

## Pinned platform surfaces

Some surfaces are **pinned**: fixed, toggle-only, cannot be moved or hidden.

- **Capabilities API** — one surface exposing every device capability, with a master on/off and
  per-capability on/off. Every capability is **always listed**; anything unusable is greyed out with
  the reason shown, never omitted. Also served over the embedded server.
- **Security lock** — biometric unlock with a user-configurable inactivity policy, from immediate
  to one hour. The lock sits ahead of every App surface and no App toggle can bypass it.

---

## Module layout

```
:core              the core library — capabilities, transport, storage, security,
                   UI kit, embedded server. Has no features of its own.

:apps              your Apps. Depends on :core only, and cannot reach the shell.

:app              the Platform App shell — navigation, page hosts, App discovery and
                   rendering, App toggle state, pinned Platform Features.
                   No features of its own.

buildSrc          generates the App registry from @PlatformAppDefinition declarations.
```

Dependencies run one way: `:app` depends on `:core` and `:apps`, and `:apps` depends only on
`:core`. A `:core` or `:apps` reference to shell code is a **compile error**.

### Why Apps get their own module

The split makes the central rule mechanical instead of conventional. When Apps lived inside the
shell module, an App could import platform UI and still compile — the "never write platform code"
rule depended on everyone remembering it. Now `:apps` declares only `:core`, so reaching for the
shell is a build failure rather than a review comment.

It also means editing an App does not recompile the shell, Apps can be tested in isolation, and
deleting an App is provably clean because no shell file can reference it.

### One installable app, however many Apps you write

An **App** here is a feature you write, not software you install. Everything — the platform and
every App — compiles into a single package:

```
applicationId = "com.personal.base"
└── one APK: platform + all Apps
```

You install that once. Every new App is folded into the next build; there is nothing to install per
App, and Apps are not downloaded or loaded at runtime.

The corollary: adding an App requires a rebuild. Shipping Apps that install independently at
runtime would be a different, much larger capability.

---

## Invariants

These must never break:

1. **No feature code on the platform.** Apps live in Apps; capabilities are completed in core.
2. **One instance per enabled App.** Disabled → no instance, no execution, state cleared.
3. **Capabilities are shared**, never duplicated per App.
4. **Logs are a compulsory one-way sink.** Everything reaches the Logs page.
5. **Every App appears on the Apps page** with a working toggle the user alone controls.
6. **The Dashboard starts empty**, and the user controls hiding and ordering.
7. **Apps are not forced** to use the dashboard or the settings.
8. **Adding an App never requires editing the shell.**
9. **Platform settings and App settings are distinct.**
10. **An App's size is unconstrained** — the contract does not assume an App is small.

---

### Keeping the phone reachable

A foreground service keeps the **process** alive. It does not keep the **CPU** running. Once the
screen is off and the device goes idle, Android suspends the CPU, the listening socket survives, and
nothing accepts or answers — which looks exactly like the server dying.

So the always-on host does two things a plain foreground service does not:

- **Holds a partial wake lock** for as long as it is switched on, released the moment you turn it
  off. No timeout: a finite one would let the device fall asleep again with the host still on.
- **Watches its own socket.** The watchdog is registered against the server's real health signal, so
  a socket that closes is reopened instead of leaving the phone unreachable until you restart it by
  hand.

It also asks for the **battery-optimisation exemption**, because Android dozes an idle app
regardless of any wake lock. Settings → Always-on Platform Host shows this when it is missing, and
links to the grant. Without it the host will still go quiet shortly after the screen goes dark, and
the symptom points nowhere near the setting that causes it.

On Android 15+ the `dataSync` foreground service type carries a six-hour daily cap. Past that the
system stops the host, and it becomes unreachable again — a limit worth knowing about before you
rely on it.

### Security

- **Signed commands** — HMAC-SHA256 over the command fields, verified against the shared secret.
- **Anti-replay window** — commands outside the TTL are rejected as expired or clock-drifted.
- **Role-based access control** — `GUEST`, `AUTHENTICATED` and `ADMIN` gate sensitive hardware such
  as location, camera, contacts and device controls.
- **Safe facade** — Apps reach capabilities through `PlatformContext` rather than raw Android APIs,
  so permission checks cannot be skipped.

---

## The HTTP surface

The always-on platform host runs one server, on **port 8899**. Every request must carry the bearer
token from **Settings → API access token**, which can be rotated there.

```bash
TOKEN=...   # Settings → API access token
H="Authorization: Bearer $TOKEN"

# the full, self-describing reference
curl -H "$H" http://PHONE:8899/api/capabilities/docs

# every capability with its status and the actions it supports
curl -H "$H" http://PHONE:8899/api/capabilities

# post a notification
curl -H "$H" -X POST http://PHONE:8899/api/capabilities/invoke \
  -d '{"capability":"NOTIFICATIONS","action":"post","title":"Hello","message":"from curl"}'

# take a photo
curl -H "$H" -X POST http://PHONE:8899/api/capabilities/invoke \
  -d '{"capability":"CAMERA","action":"capture"}'

# switch a capability off — the API then refuses to touch it
curl -H "$H" -X POST http://PHONE:8899/api/capabilities/toggle \
  -d '{"capability":"CAMERA","enabled":false}'
```

| Endpoint | Purpose |
|---|---|
| `GET /api/capabilities` | Every capability with status and supported actions |
| `GET /api/capabilities/docs` | Full reference, generated from the implementation |
| `POST /api/capabilities/invoke` | Run a capability action |
| `POST /api/capabilities/toggle` | Switch one capability, or all of them |
| `GET /api/apps/<id>/<path>` | Endpoints published by Apps that serve |

### The console

`index.html` is a browser front end for everything above. **The phone serves it**, at its own root:

```
http://100.101.227.77:8899/          # from your laptop, over Tailscale
http://localhost:8899/              # from the phone's own browser
```

Type the token from Settings → API access token into the page, and it renders the entire capability
surface as working forms.

| | |
|---|---|
| **Server** | Editable in the page. Defaults to the phone's Tailscale address |
| **Token** | From Settings → API access token. Kept in `localStorage`, never sent anywhere but your phone |
| **Sidebar** | Every capability, split by whether it has actions, with a live status dot |
| **Actions** | A form per action, built from its own declared parameters |
| **Fill from example** | Pre-fills the form from the documented example request |
| **Switches** | Per-capability and master toggles, straight from the sidebar |

**Why it is served rather than opened from disk:** a `file://` page cannot talk to the phone. The
browser sends an `OPTIONS` preflight for the `Authorization` header and the server does not answer
one, so the request never leaves. Served from the phone, the page and the API share an origin, so no
preflight happens and no CORS configuration is needed or wanted. Opening the repository copy from
disk now says exactly that, and links to the address that works.

The one path served without a token is the console itself, because a browser cannot attach an
`Authorization` header to a plain navigation — the page could otherwise never load, and could never
show the form that asks for the token. It is a static page holding no secrets, matched exactly
rather than by prefix, and **every endpoint it drives still requires the token**.

`index.html` at the repository root is the single editable copy. A Gradle task copies it into the
APK's assets on every build, and the generated copy is gitignored — so there is no second version of
the truth to forget to update.

`tools/check-console.js` renders the page against the real docs payload and checks that every action
gets a control for each of its parameters, that a capability without actions invents none, that a
hostile capability name cannot inject HTML, and that the served asset cannot drift from the source.
Regenerate the fixture with `./gradlew :core:testDebugUnitTest --tests '*DocsFixtureTest'`.

### SMS and Call history

These cannot be granted from a runtime dialog, and — as measured on a real Android 16 device —
**their grant state cannot be read back either**. Every obvious way fails:

- `AppOpsManager.unsafeCheckOpNoThrow("READ_SMS", …)` throws
  `IllegalArgumentException: Unknown operation string: READ_SMS`; these operations have left the
  public table.
- `AppOpsManager.strToOp` is hidden and absent, so there is no int fallback to reach.
- `checkSelfPermission` is not merely conservative but **wrong** — `READ_CALL_LOG` reports granted
  while reading the call log still throws `SecurityException`.

So the platform does not ask whether SMS is granted. It **tries**: a provider registers a probe that
performs the real operation and reports whether it worked. That is the only answer that cannot be a
lie, and it means a granted permission finally reads as granted.

```bash
-d '{"capability":"SMS","action":"list","limit":3}'
-d '{"capability":"SMS","action":"count"}'
-d '{"capability":"SMS","action":"read","id":41}'
```

A refused read answers `403` naming the screen where the grant lives, never an empty result — an
empty inbox and a blocked inbox look identical otherwise, and that difference is how a phone
quietly stops reporting its messages.

Grant it from **Settings → Apps → Special app access → SMS and Call history**. Nothing else can,
including `adb`: the uid-level mode the provider reads is not writable from a shell.

### The gate is authoritative

A capability you switch off is **never invoked**. The check happens before the action is
dispatched, so the API returns `403` rather than quietly doing nothing. Disabled capabilities remain
listed, greyed, so the full surface stays discoverable. The same rule covers Apps: a stopped App
answers `503` on its own endpoints.

The gate is not checked at the HTTP route — it is checked in `CapabilityApi.invokeGated`, the single
door into a capability. So it holds for **every** caller: the API, an App, and the user's own thumb
pressing a notification button.

### Actionable notifications

A notification can open a link when tapped, and can carry up to three inline buttons:

```bash
curl -H "Authorization: Bearer $TOKEN" -X POST http://PHONE:8899/api/capabilities/invoke \
  -d '{"capability":"NOTIFICATIONS","action":"post","title":"Server down",
       "message":"web-01 stopped responding",
       "buttons":[
         {"id":"restart","label":"Restart","icon":"refresh","invoke":"DEVICE_ACTIONS:vibrate"},
         {"id":"reply","label":"Reply","icon":"reply",
          "input":{"key":"text","hint":"Type a reply"},
          "invoke":"NOTIFICATIONS:post:text"}
       ]}'
```

| Field | Meaning |
|---|---|
| `id` | Your handle for the button; appears in the log |
| `label` | The text on the button |
| `icon` | `check` \| `alarm` \| `reply` \| `delete` \| `refresh` \| `open` |
| `input` | `{key, hint}` — a reply field. Requires `invoke` to name where the text goes |
| `invoke` | `CAPABILITY:action`, or `CAPABILITY:action:param` to send typed text there |
| `dismisses` | Clear the notification afterwards. Default `true` |

What happened is readable, because a button that quietly did nothing would be worse than no button:

```bash
curl -H "Authorization: Bearer $TOKEN" -X POST http://PHONE:8899/api/capabilities/invoke \
  -d '{"capability":"NOTIFICATIONS","action":"buttonLog"}'
```

Three rules keep this honest:

- **Pressing a button runs a capability through the gate.** Switch that capability off and the press
  is refused, exactly as an API call would be.
- **Text the user typed is never discarded.** A button that takes input but names no destination for
  it cannot be constructed.
- **Buttons that will not be shown are reported.** Android renders three at most, and buttons are
  dropped rather than silently drawn as dead ends — the response lists `buttonsDropped`.

Presses are delivered as a broadcast, so they work whether or not the app was running: Android
starts the process to handle one.

### Every API must be documented — this is enforced

Documentation is part of each endpoint's *definition*, not a document kept beside it:

```kotlin
ActionSpec(
    "capture", "Take a photo and save it as JPEG",
    params = listOf(ParamDoc("cameraId", "string", false, "Which camera", "0")),
    example = """{"capability":"CAMERA","action":"capture"}""",
    returns  = """{"file":"/data/.../capture.jpg","bytes":482913}""",
    handler  = { p -> cameraCapture(p) }
)
```

An action without a summary or an example **cannot be constructed** — the build fails with
`Action 'x' must have a summary`. The catalogue, the docs endpoint and the dispatcher all read the
same object, so documentation cannot drift from behaviour. Apps are held to the same rule: a route
published without a summary fails to compile, and shows up in `/api/capabilities/docs` generated
from your own declarations.

A capability whose provider cannot genuinely do something advertises no actions at all, rather than
claiming an action it cannot perform.

---

## Building

Requires JDK 17 and Android SDK 36.

```bash
./gradlew :app:assembleDebug        # build the debug APK
./gradlew test                     # run all unit tests
./gradlew :core:testDebugUnitTest  # core library tests only
```

APK output: `app/build/outputs/apk/debug/app-debug.apk`

The embedded server listens on port **8899**, and the capability surface is served at
`/api/capabilities`. The server currently runs without authentication — treat it as a development
surface and do not expose it to an untrusted network.