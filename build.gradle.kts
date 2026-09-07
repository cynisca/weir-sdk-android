// Root build file for the Weir Android SDK.
// Versions pinned to the known-good set the Niyat app already builds with on
// this machine (AGP 8.7.3 / Kotlin 2.1.0 / Gradle 8.11.1) to de-risk the toolchain.
plugins {
    id("com.android.application") version "8.7.3" apply false
    id("com.android.library") version "8.7.3" apply false
    id("org.jetbrains.kotlin.android") version "2.1.0" apply false
    id("org.jetbrains.kotlin.plugin.serialization") version "2.1.0" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.1.0" apply false
}

// Published Maven coordinates for every module (RFC-015 §12.5 / ROADMAP §6.4).
//
// Both are overridable from the command line so the public JitPack mirror
// (github.com/cynisca/weir-sdk-android) can build the *same* sources under
// JitPack's own coordinate space without a source diff:
//
//   -PweirGroup=com.github.cynisca.weir-sdk-android -PweirVersion=1.1.0
//
// The group override is load-bearing, not cosmetic: :weir-observe and :weir
// declare `api(project(":weir-core"))`, and AGP writes that into the published
// POM using weir-core's project.group. Left as `studio.aldric`, a consumer
// resolving weir-observe from JitPack would chase `studio.aldric:weir-core`
// against Maven Central and fail. Driving the group through project.group keeps
// the POM's transitive edge inside whichever repository published it.
allprojects {
    group = (findProperty("weirGroup") as String?) ?: "studio.aldric"
    version = (findProperty("weirVersion") as String?) ?: "1.1.0"
}
