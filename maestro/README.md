# Compose gallery device journeys

These Maestro flows exercise the native Compose gallery app. They are not a
remote-delivery or third-party-host suite: the gallery copies bundled fixtures
from its APK and presents them through the real renderer.

They target `studio.aldric.weir.demo` and drive the real `Weir.present` call
directly from `MainActivity`'s specimen gallery. The Android 15 Mini matrix
passed on 2026-08-03; see
[`../../docs/verification/sdk-android-compose-maestro-2026-08-03.md`](../../docs/verification/sdk-android-compose-maestro-2026-08-03.md).

## Gallery and scope

`MainActivity` renders one scrollable list: thirteen stock-screen specimens
plus an “Everything flow” row. Selecting a row calls
`Weir.present(flowId, configRoot = <fixtures copied from APK assets>, ...)`
directly. A status pill (`STATUS <value>`) reports the completion callback.
The app deliberately does not include remote-config wiring in this gallery.

| File | Criterion | What it drives |
| --- | --- | --- |
| `permission-prime-grant.yaml` | Permission grant reaches completion | API 33+ `POST_NOTIFICATIONS` dialog → Allow → completed status. |
| `permission-prime-deny.yaml` | Permission denial reaches completion | Same dialog → Android 15 “Don't allow” → completed status. |
| `kill-and-resume.yaml` | Kill/relaunch containment | Force-stop during Welcome → clean gallery relaunch. It does not claim in-memory state restoration. |
| `offline-fallback.yaml` | Network-independent render | Airplane mode → bundled Welcome specimen → completed status. This is not promoted-remote fallback evidence. |
| `crash-containment.yaml` | Host failure containment | Debug-only missing-flow probe → `Weir.present` failure callback → gallery remains usable. It is absent from release builds. |

The permission flows prove a real system dialog and a completed flow, but the
gallery does not surface the captured `resultVariable`. They do not claim a
separate UI readback of the literal granted/denied value.

Run them only on an already-running Android device/emulator. Do not create,
boot, erase, or reset a shared emulator as part of this suite.

## Mini command sequence

The Mini's non-interactive shell needs explicit tool locations:

```sh
export JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home
export ANDROID_HOME="$HOME/Library/Android/sdk"
export PATH="$HOME/.maestro/bin:$PATH"
export DEVICE_ID=emulator-5554

cd ~/weir/sdk-android
./gradlew :weir:testDebugUnitTest :demo:assembleDebug --console=plain
"$ANDROID_HOME/platform-tools/adb" -s "$DEVICE_ID" install -r \
  demo/build/outputs/apk/debug/demo-debug.apk
```

Run the flows serially. Android retains runtime permission decisions outside
the app's normal data store, so explicitly revoke only the demo's notification
permission before each dialog path. This is test setup; it does not affect any
other app or system permission.

```sh
ADB="$ANDROID_HOME/platform-tools/adb"

"$ADB" -s "$DEVICE_ID" shell pm revoke \
  studio.aldric.weir.demo android.permission.POST_NOTIFICATIONS
maestro test --device "$DEVICE_ID" permission-prime-grant.yaml

"$ADB" -s "$DEVICE_ID" shell pm revoke \
  studio.aldric.weir.demo android.permission.POST_NOTIFICATIONS
maestro test --device "$DEVICE_ID" permission-prime-deny.yaml

maestro test --device "$DEVICE_ID" offline-fallback.yaml
"$ADB" -s "$DEVICE_ID" shell cmd connectivity airplane-mode disable

maestro test --device "$DEVICE_ID" kill-and-resume.yaml
```

The explicit airplane-mode disable is a safety restoration step if the offline
flow aborts before its own final command. Check it before handing a shared
emulator back:

```sh
"$ADB" -s "$DEVICE_ID" shell settings get global airplane_mode_on
# Expected: 0
```

`crash-containment.yaml` requires the debug gallery APK. Its deliberate
missing-flow probe is test-only and is absent from release builds.
