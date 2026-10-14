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
@PlatformAppDefinition(id = "hello", name = "Hello", order = 20)
class HelloApp : PlatformApp {

    override val id = "hello"
    override val name = "Hello"
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

## Security

- **Signed commands** — HMAC-SHA256 over the command fields, verified against the shared secret.
- **Anti-replay window** — commands outside the TTL are rejected as expired or clock-drifted.
- **Role-based access control** — `GUEST`, `AUTHENTICATED` and `ADMIN` gate sensitive hardware such
  as location, camera, contacts and device controls.
- **Safe facade** — Apps reach capabilities through `PlatformContext` rather than raw Android APIs,
  so permission checks cannot be skipped.

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