# Building an App

Everything an App author needs. Follow this and your App will look native, behave correctly, and
never require touching the platform shell.

---

## 1. Declare it — one annotated file

Apps live in the **`apps/`** module. Create a folder under
`apps/src/main/java/com/personal/base/apps/` and add one file:

```kotlin
package com.personal.base.apps.myapp

@PlatformAppDefinition(id = "my-app", name = "My App", order = 10)
class MyApp : PlatformApp { ... }
```

- `id` is permanent. It keys the toggle, the notification channel, and server routes. Changing it
  orphans the user's saved state.
- `order` sorts the Apps page. Lower comes first.
- The registry is generated at build time. **Never hand-edit a registry, never edit the shell.**
- An App may be one file or a large system. Put related files in a folder next to the annotated one.

### Nothing to install

An App is code you write, not something you install. The platform and every App compile into **one
APK** (`com.personal.base`). You install that once; each new App is folded into the next build.

```
apps/  ──build──►  app-release.apk  ──install──►  phone
```

There is no per-App install step, and Apps are not downloaded or loaded at runtime. Adding an App
means rebuilding.

## 2. Implement the contract

| Member | Required | Notes |
|---|---|---|
| `id`, `name`, `icon`, `description`, `version` | yes | Identity, shown on the Apps page |
| `contribution` | no | Dashboard card / actions / settings section |
| `onInitialize` | no | Called once when the user switches the App on |
| `onDestroy` | no | Called when switched off — clear your state here |
| `onPeriodicSync` | no | Background work |
| `handleCommand` | no | Command handling |
| `AppContent` | no | Your own full screen |

Everything is optional. A headless App that only syncs in the background is normal.

## 3. Respect the toggle

- **Off → no instance exists.** Nothing you schedule runs. No timers, no coroutines, no listeners.
- **Off → your state is cleared.** Release resources and reset fields in `onDestroy`.
- **On → exactly one instance.** Never create a second copy of yourself.
- Do not add your own `enabled` flag. The platform owns that; a second source of truth will drift.

## 4. Use `PlatformContext`, never raw Android APIs

Apps reach capabilities through the safe facade:

```kotlin
val camera = context.getCapabilityByClass(CameraMicCapabilityProvider::class.java)
context.triggerHaptic(200L)
context.postNotification("Title", "Body")
```

- Never request a permission yourself — the platform does it, once, centrally.
- Never hold a `Context` you did not receive. Use `context` from `onInitialize`.
- Capabilities are **shared**. Do not instantiate a capability provider yourself; ask the registry.

## 5. Follow the UI kit

The design system lives in **core**, so an App in any module gets it for free.

**Import from `com.personal.base.core.ui.theme`:**

| Use | From |
|---|---|
| Theme | `PersonalBaseTheme` — Material 3 with dynamic colour |
| Card | `PlatformCard(title, subtitle) { … }` |
| Section heading | `PlatformSectionTitle("…")` |
| Explanatory text | `PlatformHint("…")` |
| Labelled switch row | `PlatformSwitchRow(title, description, checked) { … }` |
| Spacing rhythm | `PlatformSpacing.small / medium / large` |

Rules:

- **Never a literal colour.** Use `MaterialTheme.colorScheme.*`. `Color(0xFF6200EE)` breaks dark
  mode and dynamic colour.
- **Never a raw font size.** Use `MaterialTheme.typography.*`.
- **Never define your own palette, text style, or shapes.**
- Icons come from `androidx.compose.material.icons`.
- Prefer the kit components over hand-rolled `Card` + `Column` + `Text` stacks; they encode the
  platform's spacing and styling rules.

### Do not repeat the page title

The shell's app bar already names the current page. A page must not render its own title — that is
duplicated text.

```kotlin
// Wrong — "My App" appears twice
Text("My App", style = MaterialTheme.typography.titleMedium)

// Right — the app bar says it already
PlatformCard { /* content */ }
```

The same applies to a back button: the app bar provides one.

## 6. Non-tab pages: hide the bottom nav

A page reached from inside another page is an **overlay**, not a tab.

- Register it in `OverlayDestination`.
- The shell then hides the bottom navigation automatically.
- The app bar shows the overlay's own title and a back arrow.

```kotlin
enum class OverlayDestination(val title: String) {
    MY_PAGE("My Page")
}
```

Never render the bottom bar yourself, and never make a non-tab page look like a tab.

## 7. System back must navigate back

System back must **never close the app** while a page is open.

- The shell installs a `BackHandler` per overlay — that covers overlays.
- If your App renders its own screen, it must offer a way back:

```kotlin
BackHandler { container.appManager.closeActiveApp() }
```

- Back should always move **one level**: screen → dashboard → exit. Never skip a level, never
  discard state without confirmation.

## 8. Logs are compulsory and one-way

- Use `context.logger` for anything worth diagnosing.
- Those logs flow to the Logs page automatically.
- **Hiding or filtering your own logs out of the Logs page is not allowed.** The Logs page is a
  platform surface, and App logs always reach it.
- Tag consistently so logs stay attributable after the App is deleted.

## 9. Notifications

Post through the **App scope**, not `context`:

```kotlin
// in a card or your screen
scope.postNotification("Webcam", "Streaming started")

// from onInitialize you hold a PlatformContext, so ask the manager:
container.appManager.appScope(appId).postNotification("Ready", "Client connected")
```

`scope.postNotification` targets **your App's own channel**, creating it on first use. That is what
makes the per-App mute control in Settings work.

- `context.postNotification(...)` posts to a **platform** channel — platform-internal only. An App
  using it will land in the platform's group and cannot be silenced per App.
- Never create channels yourself.
- Never touch a platform channel id.
- The user silencing your App never affects the platform or any other App.

## 10. Serving over the network

The platform owns one server and one port. An App contributes routes to it by implementing
`AppServer`:

```kotlin
@PlatformAppDefinition(id = "webcam", name = "Webcam", order = 10)
class WebcamApp : PlatformApp, AppServer {

    override val id = "webcam"
    override val name = "Webcam"
    override val icon = Icons.Default.Videocam
    override val description = "Serves the camera."
    override val version = "1.0"

    override fun install(registrar: AppRouteRegistrar) {
        registrar.get("status") { ServerResponse.json("""{"streaming":$streaming}""") }
        registrar.get("clients") { ServerResponse.json("""{"clients":${clients.size}}""") }
    }

    override fun uninstall() {
        // release the camera here
    }
}
```

Your endpoints are namespaced automatically:

```
/api/apps/webcam/status
/api/apps/webcam/clients
```

Rules:

- **Never open your own socket.** The platform runs the server; you contribute routes.
- **The toggle gates every request.** A stopped App answers `503`, immediately, without the route
  being removed. That is the user's safety switch, expressed over HTTP.
- `install` runs when the App is switched on, `uninstall` when it is switched off. Release hardware
  in `uninstall`.
- Nothing is reachable while the **always-on host** (Settings → Always-on Platform Host) is off.
- The server has **no authentication**. Do not expose anything private on it.

## 11. Never modify the platform

- **Apps live in `apps/`, not in the shell.** `apps/` depends on `:core` only — it cannot import the
  shell, because the shell depends on it, not the other way round.
- **No new code in the shell.** If you need a capability that does not exist, complete the core
  capability, then consume it.
- Do not edit the Apps page, Dashboard, Logs, or Settings to special-case your App.
- Do not add a permission to the manifest — extend the core permission model instead.
- If a Platform Feature genuinely must change, say so rather than working around it.

### Module layout

```
:core    capabilities, UI kit, App contract, server        ← Apps may use this
:apps    your Apps                                          ← depends on :core only
:app     the shell that hosts Apps                         ← depends on :core + :apps
```

To add a whole new *module* of Apps, add it to `settings.gradle.kts` and one line to `appModules` in
`app/build.gradle.kts`. That is the only place a module is listed, and it is not feature code.

## 12. Before you ship

- [ ] Annotated with `@PlatformAppDefinition`; stable `id`
- [ ] No shell file edited
- [ ] `onDestroy` releases everything; no timers or listeners survive a toggle-off
- [ ] No hardcoded colours, fonts, or theme values
- [ ] No duplicated page title or back button
- [ ] System back navigates back, never exits
- [ ] Non-tab page registered as an `OverlayDestination`
- [ ] Capabilities reached through `PlatformContext`
- [ ] Logs flow to the Logs page
- [ ] Toggle-off verified on a device
- [ ] Works after a restart with the toggle restored

---

## Quick reference

| I want to… | Do this |
|---|---|
| Add a feature | Write one annotated `PlatformApp` — nothing else |
| Add a dashboard card | `contribution.dashboardCard` |
| Add a dashboard action | `contribution.dashboardActions` |
| Add settings | `contribution.settingsSection` |
| Add a full screen | Override `AppContent` |
| Use a device capability | `context.getCapabilityByClass(...)` |
| Add a non-tab page | Add to `OverlayDestination` |
| Serve a capability | Implement `AppServer`, register routes |
| Match the platform look | `PlatformCard`, `PlatformHint`, `MaterialTheme` tokens |
| Add spacing | `PlatformSpacing.small / medium / large` |
| Show a labelled switch | `PlatformSwitchRow { checked = it }` |