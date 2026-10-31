import java.io.File
import org.gradle.api.GradleException

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// ---------------------------------------------------------------------------
// Release signing
//
// No key material or password ever lives in this repository. CI exports the
// credentials as environment variables (from GitHub Actions Secrets); local
// builds load them through `scripts/release-env.ps1`. When the variables are
// absent the release variant stays unsigned and the build keeps working; CI
// sets AGENTIME_REQUIRE_SIGNING=true so a missing/typo'd secret fails loudly
// instead of shipping an unsigned candidate as if it were signed.
// ---------------------------------------------------------------------------
val releaseStoreFile: String? = System.getenv("AGENTIME_STORE_FILE")?.trim()?.takeIf { it.isNotEmpty() }
val releaseStorePassword: String? = System.getenv("AGENTIME_STORE_PASSWORD")?.takeIf { it.isNotEmpty() }
val releaseKeyAlias: String? = System.getenv("AGENTIME_KEY_ALIAS")?.takeIf { it.isNotEmpty() }
val releaseKeyPassword: String? = System.getenv("AGENTIME_KEY_PASSWORD")?.takeIf { it.isNotEmpty() }
val releaseStoreFileResolved: File? = releaseStoreFile?.let { file(it) }
val releaseSigningReady: Boolean =
    releaseStoreFileResolved != null &&
        releaseStoreFileResolved.isFile &&
        releaseStorePassword != null &&
        releaseKeyAlias != null &&
        releaseKeyPassword != null

// Internal-test candidate. Override for an upgrade test, e.g.
//   gradle -PappVersionCode=3 -PappVersionName=0.2.0-internal2 assembleRelease
val appVersionCode: Int = (project.findProperty("appVersionCode") as String?)?.toInt() ?: 2
val appVersionName: String = (project.findProperty("appVersionName") as String?) ?: "0.2.0-internal1"

android {
    namespace = "app.agentime"
    compileSdk = 35
    ndkVersion = "27.0.12077973"

    defaultConfig {
        applicationId = "app.agentime"
        minSdk = 26
        targetSdk = 35
        versionCode = appVersionCode
        versionName = appVersionName
        // Backend base URL is configurable; no secrets here. Use cleartext only for local dev.
        buildConfigField("String", "DEFAULT_BACKEND", "\"http://10.0.2.2:8787\"")

        // librime + the whole native dependency closure is vendored per ABI in
        // src/main/jniLibs; keep the set explicit so a stale .so cannot leak in.
        ndk {
            abiFilters.addAll(listOf("arm64-v8a", "x86_64"))
        }
        externalNativeBuild {
            cmake {
                arguments += listOf("-DANDROID_STL=c++_shared")
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            // 3.31.6 (SDK package) is required: CMake 3.22.1 crashes with
            // STATUS_STACK_BUFFER_OVERRUN when the project path contains
            // non-ASCII characters (e.g. "agent输入法").
            version = "3.31.6"
        }
    }

    packaging {
        // librime's .so files are prebuilt and must be shipped verbatim;
        // legacy packaging keeps them uncompressed and directly mmap-able.
        jniLibs {
            useLegacyPackaging = true
        }
    }

    buildFeatures {
        buildConfig = true
        viewBinding = true
    }

    signingConfigs {
        if (releaseSigningReady) {
            create("release") {
                storeFile = releaseStoreFileResolved
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            if (releaseSigningReady) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
}

// CI must never publish an unsigned release candidate by accident.
tasks.matching { it.name == "packageRelease" }.configureEach {
    doFirst {
        if (!releaseSigningReady && System.getenv("AGENTIME_REQUIRE_SIGNING") == "true") {
            throw GradleException(
                "Release signing credentials are missing. Set AGENTIME_STORE_FILE, " +
                    "AGENTIME_STORE_PASSWORD, AGENTIME_KEY_ALIAS and AGENTIME_KEY_PASSWORD " +
                    "(or run scripts/release-env.ps1 locally). See docs/release-signing.md."
            )
        }
    }
}
