plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("maven-publish")
}

android {
    namespace = "studio.aldric.weir.observe"
    compileSdk = 35

    defaultConfig {
        minSdk = 26
        @Suppress("DEPRECATION")
        targetSdk = 35
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release { isMinifyEnabled = false }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            isReturnDefaultValues = true
        }
    }
    publishing {
        singleVariant("release") { withSourcesJar() }
    }
}

publishing {
    publications {
        create<MavenPublication>("release") {
            // groupId/version track project.group/project.version (root build.gradle.kts)
            // so the JitPack mirror can republish these sources under its own coordinates.
            groupId = project.group.toString()
            artifactId = "weir-observe"
            version = project.version.toString()
            afterEvaluate { from(components["release"]) }
        }
    }
}

dependencies {
    api(project(":weir-core"))

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.14.1")
    testImplementation("androidx.test:core:1.6.1")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1")
}
