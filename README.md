# Weir Android SDK

Public distribution mirror of the Weir Android SDK, published via
[JitPack](https://jitpack.io/#cynisca/weir-sdk-android).

**This repository is generated.** It is re-cut from the Weir monorepo's
`sdk-android/` directory by `scripts/sync-sdk-android-mirror.sh` on every
release. Pull requests here cannot be merged upstream — please open an issue
instead.

Synced from monorepo commit `351883f6c328df999b1d6441bb1e995e38c1591f`.

## Install

`settings.gradle.kts`:

```kotlin
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven { url = uri("https://jitpack.io") }
    }
}
```

`build.gradle.kts`:

```kotlin
dependencies {
    // Analytics only — no UI, no Compose. See "Modules" below.
    implementation("com.github.cynisca.weir-sdk-android:weir-observe:1.1.0")

    // The full Compose flow renderer (pulls weir-core transitively).
    // implementation("com.github.cynisca.weir-sdk-android:weir:1.1.0")

    // Queue / envelope / identity primitives on their own.
    // implementation("com.github.cynisca.weir-sdk-android:weir-core:1.1.0")
}
```

## Modules

| Module | Coordinate | Compose? | What it is |
|---|---|---|---|
| `weir-observe` | `com.github.cynisca.weir-sdk-android:weir-observe:1.1.0` | **No** | `Weir.observe()` / `Weir.screen()` funnel instrumentation for an onboarding you already built. |
| `weir-core` | `com.github.cynisca.weir-sdk-android:weir-core:1.1.0` | **No** | Event queue, envelopes, identity, signed-bundle plumbing. |
| `weir` | `com.github.cynisca.weir-sdk-android:weir:1.1.0` | Yes | The Jetpack Compose renderer for v4 flow specs. |

`weir-observe` and `weir-core` do not apply the Compose compiler plugin and
carry no `androidx.compose` dependency — an app that only instruments its own
screens never links the renderer (RFC-015 §12.5).

## Requirements

| | |
|---|---|
| minSdk | 26 |
| compileSdk | 35 |
| JDK | 17 |
| Kotlin | 2.1.0 |
| Android Gradle Plugin | 8.7.3 |

## Documentation

<https://weir-onboarding-2026.web.app/docs>

## License

MIT — see [LICENSE](LICENSE).
