plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("org.jetbrains.kotlin.plugin.compose")
    id("maven-publish")
}

android {
    namespace = "studio.aldric.weir"
    compileSdk = 35

    buildFeatures {
        compose = true
        // RC-P0 (release-gate re-review): generates studio.aldric.weir.BuildConfig
        // with a real BuildConfig.DEBUG constant, so ComponentRegistry's registry-
        // drift assertion can be gated by build type rather than a manually-set
        // flag that defaulted to "always crash" in every build, release included.
        buildConfig = true
    }

    defaultConfig {
        minSdk = 26
        // targetSdk lives under `testOptions`/consumers for a library, but AGP
        // still accepts it in defaultConfig for lint/test parity with the host app.
        @Suppress("DEPRECATION")
        targetSdk = 35
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        consumerProguardFiles("consumer-rules.pro")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    testOptions {
        unitTests {
            // Robolectric needs the merged Android resources/manifest to stand
            // up a real-ish framework (SharedPreferences and Activity hosts).
            isIncludeAndroidResources = true
            isReturnDefaultValues = true
        }
    }

    // Publish the `release` variant as a Maven artifact (founder decision P1:
    // Niyat consumes Weir via Maven Local). `withSourcesJar()` ships a
    // -sources.jar alongside the AAR so the Niyat devs get symbol navigation.
    publishing {
        singleVariant("release") {
            withSourcesJar()
        }
    }
}

// Maven coordinate: <project.group>:weir:<project.version> — studio.aldric:weir:1.1.0
// from `mavenLocal()`, com.github.cynisca.weir-sdk-android:weir:<tag> from JitPack.
publishing {
    publications {
        create<MavenPublication>("release") {
            // groupId/version track project.group/project.version (root build.gradle.kts)
            // so the JitPack mirror can republish these sources under its own coordinates.
            groupId = project.group.toString()
            artifactId = "weir"
            version = project.version.toString()

            // The `release` software component only exists after AGP has
            // finished configuring the variant, hence afterEvaluate. Wiring it
            // via `from(components["release"])` makes AGP emit the POM with the
            // library's api/implementation deps as transitive <dependency>
            // entries (implementation -> runtime scope, api -> compile scope).
            afterEvaluate {
                from(components["release"])
            }
        }
    }
}

dependencies {
    // Compose-free queue/envelope/identity primitives are published separately
    // and remain part of this renderer's transitive public API.
    api(project(":weir-core"))

    // Bridge JSON (mirrors sdk-ios's Codable/JSONValue handling). `api` because
    // public bridge types (WeirVariable.value, the envelope params/result) are
    // JsonElement-typed — a consumer reading a completed flow's variables needs
    // kotlinx-serialization on its compile classpath.
    api("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.2")

    // Ed25519 signature verification for the OTA BundleManager (plan §3 + §9
    // R1). BouncyCastle *lightweight* API only (Ed25519Signer +
    // Ed25519PublicKeyParameters) — NOT the JCA/JCE provider: Android ships an
    // old conflicting BC, and java.security Ed25519 is API 33+ while minSdk is
    // 26. No Security.addProvider registration anywhere.
    implementation("org.bouncycastle:bcprov-jdk18on:1.78.1")

    // Native engine, permission, and purchase async work.
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.1")

    // Presentation host: WeirFlowActivity is a ComponentActivity and
    // owns the runtime-permission ActivityResultLauncher. `api` because
    // WeirFlowActivity (public) extends ComponentActivity.
    api("androidx.activity:activity-ktx:1.9.3")
    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-text")
    implementation("androidx.compose.runtime:runtime")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.compose.material:material-icons-core")

    // ContextCompat.checkSelfPermission (permission short-circuit) + WindowCompat
    // / WindowInsetsControllerCompat (edge-to-edge + system-bar style).
    implementation("androidx.core:core-ktx:1.13.1")

    // Unit tests.
    testImplementation("junit:junit:4.13.2")

    // Android-framework-backed unit tests (SharedPreferences, Activity, Context).
    // 4.14.1 supports compileSdk 35 on AGP 8.7.x.
    testImplementation("org.robolectric:robolectric:4.14.1")
    testImplementation("androidx.test:core:1.6.1")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1")
    testImplementation(platform("androidx.compose:compose-bom:2024.12.01"))
    testImplementation("androidx.compose.ui:ui-test-junit4")
    testImplementation("androidx.compose.ui:ui-test-manifest")
}
