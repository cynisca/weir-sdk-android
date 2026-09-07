# Weir Observe for Android

`:weir-observe` is the hand-instrumented onboarding observability facade. It
depends only on the Compose-free `:weir-core` module; neither module applies the
Compose Gradle plugin or declares an `androidx.compose.*` dependency.

Verify the release runtime graph from `sdk-android/`:

```sh
./gradlew :weir-observe:dependencies --configuration releaseRuntimeClasspath | grep -i compose
```

Expected output: none.
