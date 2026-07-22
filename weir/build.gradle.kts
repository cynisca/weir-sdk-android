plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("maven-publish")
}

// group/version double as the coordinate a Gradle composite build (git
// source dependency) resolves this module as — "studio.aldric:weir" — not
// just the Maven-publish coordinate below. Keep the two in sync.
group = "studio.aldric"
version = "0.1.1"

android {
    namespace = "studio.aldric.weir"
    compileSdk = 35

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
            // up a real-ish framework (SharedPreferences, WebView shadows).
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

// Maven coordinate: studio.aldric:weir:0.1.0 — consumed from `mavenLocal()`.
publishing {
    publications {
        create<MavenPublication>("release") {
            groupId = "studio.aldric"
            artifactId = "weir"
            version = "0.1.0"

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

    // Async dispatch seam — the iOS BridgeRouter's `Task { }` / `async`
    // suspend calls (permission/purchase/products) map to coroutines here.
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.1")

    // App-foreground flush trigger for the offline EventQueue — the Android
    // analogue of iOS's `UIApplication.willEnterForegroundNotification`. Only
    // ProcessLifecycleForegroundTrigger touches this; EventQueue itself stays
    // lifecycle-agnostic (injectable ForegroundFlushTrigger) so unit tests
    // don't depend on a real Android lifecycle.
    implementation("androidx.lifecycle:lifecycle-process:2.8.7")

    // Modern WebView surface (WebViewAssetLoader, addJavascriptInterface host,
    // WebViewCompat.addDocumentStartJavaScript for the __weirUserId injection).
    // Exposed as `api` because the public WebView host types reference webkit
    // types on their signatures.
    api("androidx.webkit:webkit:1.12.1")

    // Phase 3 presentation host: WeirFlowActivity is a ComponentActivity and
    // owns the runtime-permission ActivityResultLauncher. `api` because
    // WeirFlowActivity (public) extends ComponentActivity.
    api("androidx.activity:activity-ktx:1.9.3")

    // ContextCompat.checkSelfPermission (permission short-circuit) + WindowCompat
    // / WindowInsetsControllerCompat (edge-to-edge + system-bar style).
    implementation("androidx.core:core-ktx:1.13.1")

    // Unit tests.
    testImplementation("junit:junit:4.13.2")

    // Android-framework-backed unit tests (SharedPreferences, WebView, Context).
    // 4.14.1 supports compileSdk 35 on AGP 8.7.x.
    testImplementation("org.robolectric:robolectric:4.14.1")
    testImplementation("androidx.test:core:1.6.1")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1")
}
