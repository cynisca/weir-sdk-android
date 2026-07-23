# weir-sdk-android — Weir Android SDK

Kotlin port of `sdk-ios/`. This repo is a split of the `sdk-android/`
directory from the Weir monorepo, mirroring the precedent set by
[`weir-sdk-ios`](https://github.com/cynisca/weir-sdk-ios): a standalone,
independently buildable Gradle project that consuming apps pin by git URL/tag
instead of reading out of the monorepo checkout.

`:weir` is the library module (bridge, persistence/OTA `BundleManager`,
WebView host, `WeirFlowActivity` presentation surface). `:demo` is a minimal
consumer app used for local development/dogfooding via `project(":weir")` —
it is not published and is not part of the SDK's public surface.

## Toolchain

| Component | Version | Why |
|---|---|---|
| Android Gradle Plugin | 8.7.3 | Matches the known-good set Weir-onboarded apps build with. |
| Gradle | 8.11.1 | Wrapper checked in; matched to AGP 8.7.3. |
| Kotlin | 2.1.0 | `kotlinx.serialization` plugin uses the same version. |
| compileSdk / targetSdk | 35 | |
| minSdk | 26 | |
| JDK | 17 | Required to run Gradle. |

## Build & test standalone

```sh
git clone https://github.com/cynisca/weir-sdk-android.git
cd weir-sdk-android
echo "sdk.dir=/path/to/Android/sdk" > local.properties   # or export ANDROID_HOME
./gradlew :weir:assembleRelease --no-daemon    # produces the AAR
./gradlew :weir:testDebugUnitTest --no-daemon  # runs the 141 JVM unit tests
```

Both commands succeed from a clean clone with no reference to the Weir
monorepo — that's the point of the split.

## Consuming this SDK from an app

This SDK is not published to Maven Central. There are two supported ways to
pull it into an app: a Gradle composite build straight off the git tag, or
[JitPack](https://jitpack.io), which builds tagged commits of any public
GitHub repo on demand and serves them as normal Maven artifacts.

### Option A — JitPack (recommended for most consumers)

Add the JitPack repository, then depend on the tag directly — no local clone,
no composite build, works from any CI runner with normal internet access.

```kotlin
// settings.gradle.kts
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        maven(url = "https://jitpack.io")
    }
}
```

```kotlin
// app/build.gradle.kts
dependencies {
    implementation("com.github.cynisca:weir-sdk-android:0.1.1")
}
```

JitPack builds this repo with `jitpack.yml` (JDK 17) via `publishToMavenLocal`
on the `:weir` module and exposes it as a single artifact,
`com.github.cynisca:weir-sdk-android`, an AAR with the `:weir` module's
dependencies. The first resolve of a given tag triggers a build on JitPack's
side and can take a minute or two; subsequent resolves are cached. Check build
status at `https://jitpack.io/api/builds/com.github.cynisca/weir-sdk-android/0.1.1`,
or the raw log at
`https://jitpack.io/com/github/cynisca/weir-sdk-android/0.1.1/build.log`.

### Option B — Gradle composite build via git URL

Include the SDK as a source dependency straight from this repo, no local
publish step, no Maven Local involved. In your app's `settings.gradle.kts`:

```kotlin
sourceControl {
    gitRepository(java.net.URI("https://github.com/cynisca/weir-sdk-android.git")) {
        producesModule("studio.aldric:weir")
    }
}
```

Then depend on it normally in `app/build.gradle.kts`:

```kotlin
dependencies {
    implementation("studio.aldric:weir") {
        version { strictly("0.1.1") } // pins the `0.1.1` tag
    }
}
```

Gradle checks out the tagged commit, builds `:weir` as an included build, and
wires it into your dependency graph — no manual clone, no `mavenLocal()`, and
no stale local publish to go out of sync.

**Local SDK-development override:** to iterate on the SDK and an app at the
same time, temporarily swap the `gitRepository` block above for:

```kotlin
includeBuild("/absolute/path/to/weir-sdk-android") {
    dependencySubstitution {
        substitute(module("studio.aldric:weir")).using(project(":weir"))
    }
}
```

Do not commit this override — it's a local-only path, exactly like the
commented `path:` override in CutOrBulk's SwiftPM `project.yml`.

### Option C — `git clone` + `publishToMavenLocal` (manual fallback)

If your Gradle setup can't use JitPack or `sourceControl`/composite builds,
the fallback is manual:

```sh
git clone --branch 0.1.1 https://github.com/cynisca/weir-sdk-android.git
cd weir-sdk-android
./gradlew :weir:publishToMavenLocal --no-daemon
```

This publishes `studio.aldric:weir:0.1.1` into `~/.m2/repository`, i.e. your
machine's Maven Local cache. Then in the consuming app:

```kotlin
// settings.gradle.kts — add mavenLocal() to repositories
dependencyResolutionManagement {
    repositories {
        mavenLocal()
        google()
        mavenCentral()
    }
}
```

```kotlin
// app/build.gradle.kts
dependencies {
    implementation("studio.aldric:weir:0.1.1")
}
```

It works, but it is machine-local and easy to go stale (forget to re-publish
after pulling a new tag) — prefer Option A or B for anything beyond quick
local iteration.

## Layout

```
weir-sdk-android/
├── settings.gradle.kts        # includes :weir, :demo
├── build.gradle.kts           # root: AGP/Kotlin/serialization plugin pins
├── gradle.properties
├── gradlew / gradle/wrapper/   # Gradle 8.11.1 wrapper
├── weir/                       # the :weir library module (published artifact)
│   ├── build.gradle.kts
│   └── src/main/kotlin/studio/aldric/weir/...
├── demo/                        # local dev-only consumer app (not published)
└── maestro/                     # Maestro flows exercising the demo app
```

Namespace: `studio.aldric.weir`. Maven coordinate: `studio.aldric:weir`.

## Dependencies

- `org.jetbrains.kotlinx:kotlinx-serialization-json` — bridge JSON.
- `androidx.webkit:webkit` (`api`) — modern WebView / `WebViewAssetLoader`.
- `org.bouncycastle:bcprov-jdk18on` — Ed25519 signature verification for the
  OTA `BundleManager` (lightweight API only, not the JCA/JCE provider).
- `androidx.lifecycle:lifecycle-process` — app-foreground flush trigger for
  the offline event queue.
- `androidx.activity:activity-ktx` — `WeirFlowActivity`'s
  `ComponentActivity`/permission-launcher base.
- Tests: `junit`, `robolectric`, `androidx.test:core`,
  `kotlinx-coroutines-test`.

## Relationship to the Weir monorepo

This repo is split from `sdk-android/` in the main Weir monorepo
(`github.com/cynisca/weir` — private). The monorepo remains the source of
truth for SDK *development*; this repo is the distribution artifact history
(tagged releases) that consuming apps pin against, exactly mirroring
`weir-sdk-ios`. Do not hand-edit this repo directly for feature work — changes
should land in the monorepo's `sdk-android/` and get re-synced here as part of
a tagged release.

## License

MIT — see [LICENSE](LICENSE).
