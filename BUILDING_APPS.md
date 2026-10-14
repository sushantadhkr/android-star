# Building an App

Everything an App author needs. Follow this and your App will look native, behave correctly, and
never require touching the platform shell.

---

## 1. Declare it — one annotated file

```kotlin
@PlatformAppDefinition(id = "my-app", name = "My App", order = 10)
class MyApp : PlatformApp { ... }
```

- `id` is permanent. It keys the toggle, the notification channel, and server routes. Changing it
  orphans the user's saved state.
- `order` sorts the Apps page. Lower comes first.
- The registry is generated at build time. **Never hand-edit a registry, never edit the shell.**

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

- The platform gives your App **its own channel** automatically, under the **Apps** group.
- Do not create channels yourself, and do not touch platform channels.
- The user can silence your App independently — that never affects the platform or other Apps.
- Post through `context.postNotification(...)`.

## 10. Serving a capability over the network

Expose a capability as a service by implementing `CapabilityServingApp`:

```kotlin
class WebcamApp : PlatformApp, CapabilityServingApp {
    override fun httpPayload(): String = """{"streaming":true}"""
    override val servePath: String get() = "camera"
}
```

- The route is bound to the **live instance** — a stopped App serves nothing. That is correct; do
  not work around it by holding a static reference.
- Return JSON. Do not stream secrets.
- The embedded server currently runs **without authentication**. Do not expose anything private,
  and do not treat it as safe on an untrusted network.

## 11. Never modify the platform

- **No new code in the shell.** If you need a capability that does not exist, complete the core
  capability, then consume it.
- Do not edit the Apps page, Dashboard, Logs, or Settings to special-case your App.
- Do not add a permission to the manifest — extend the core permission model instead.
- If a Platform Feature genuinely must change, say so rather than working around it.

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
| Serve a capability | Implement `CapabilityServingApp` |
| Match the platform look | `PlatformCard`, `PlatformHint`, `MaterialTheme` tokens |
| Add spacing | `PlatformSpacing.small / medium / large` |
| Show a labelled switch | `PlatformSwitchRow { checked = it }` |