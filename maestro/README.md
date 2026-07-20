# Weir Android — Maestro suite

Maestro flows exercising the `:demo` app end to end on an Android emulator,
mirroring `sdk-ios/maestro/`. They target `studio.aldric.weir.demo` and drive
the real `Weir.presentActivity` → `WeirFlowActivity` path, the real
`ActivityPermissionRequester` runtime dialog, and the crash-containment
fallback.

> **Not run in this task.** Phase 3 authored these flows and the demo they
> target; **booting an emulator and running Maestro is an explicit non-goal
> here** (see the Phase 3 plan). They are a CI / manual follow-up — the demo
> `assembleDebug`s cleanly (that *is* verified), which is the prerequisite.

## Flows

| File | Criterion | What it drives |
|---|---|---|
| `offline-fallback.yaml` | Bundled fallback renders/completes | Embedded bundle renders from `assets/` via `WeirAssetLoader`; flow completes with no network in the path. |
| `kill-and-resume.yaml` | Kill-and-resume mid-flow | Force-stop mid-flow + relaunch doesn't crash; returns cleanly to the host screen (no in-memory state restoration — documented baseline, same as iOS). |
| `permission-prime-grant.yaml` | Permission grant propagates | Real `POST_NOTIFICATIONS` dialog (API 33+) → "Allow" → native banner `notifications=granted`. |
| `permission-prime-deny.yaml` | Permission deny propagates | Real dialog → "Deny" → native banner `notifications=denied`. |
| `crash-containment.yaml` | Renderer failure never crashes the host | "Start broken flow" → missing bundle → main-frame load failure → `onFailure` banner; host stays alive. |

## Running (manual / CI follow-up)

```
export ANDROID_HOME=/Users/fahim/Library/Android/sdk
# 1. Boot an API 34 emulator (33+ needed for the runtime notification dialog).
# 2. Build + install the demo:
cd sdk-android && ./gradlew :demo:installDebug
# 3. Run the suite:
maestro test sdk-android/maestro/
```

## Instrumentation in the demo (why the banners exist)

`MainActivity` shows a single status banner (a `TextView` whose `text` and
`contentDescription` Maestro asserts against). Permission outcomes reach it via
`onPermissionResult` and terminal outcomes via the `Weir.presentActivity`
completion — the Android analogue of iOS `WeirDemo`'s `ContentView` banners,
for the same reason: a `permission.request` result otherwise only reaches the
host at flow completion, too late to verify a mid-flow prime. The "Start broken
flow" button (a nonexistent bundle root) is the Android analogue of iOS's
`WEIR_DEMO_FORCE_MISSING_BUNDLE` — a real, not fabricated, load failure.

## Emulator-specific notes

- **Runtime notification dialog needs API 33+.** On an API ≤32 emulator the SDK
  auto-grants via the channel path (no dialog); adjust the grant/deny flows
  accordingly (the YAML comments call this out).
- **System dialog button labels** ("Allow"/"Deny") are locale/OEM dependent;
  the flows assume an en-US AOSP image.
- **True airplane mode** can be added to `offline-fallback.yaml` with
  `- setAirplaneMode: enabled` (Maestro 1.39+) on an emulator; the flow is
  network-agnostic regardless because the bundle references no `fetch`/XHR/WS.
