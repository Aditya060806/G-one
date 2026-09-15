import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

// ── Release signing credentials ────────────────────────────────────────────────
// Read from keystore.properties at the repo root, which is gitignored along with
// the .jks itself. Keys and passwords must never be committed, and hardcoding them
// here would put them in every clone and every CI log.
//
// If the file is absent the build still succeeds but produces an UNSIGNED release
// APK, which cannot be installed. That case is reported loudly below rather than
// discovered later at `adb install` time.
val keystorePropertiesFile = rootProject.file("keystore.properties")
val keystoreProperties = Properties().apply {
    if (keystorePropertiesFile.exists()) {
        keystorePropertiesFile.inputStream().use { load(it) }
    }
}
val hasReleaseKeystore = keystorePropertiesFile.exists() &&
    keystoreProperties.getProperty("storeFile") != null

android {
    namespace = "com.infinity.ai"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.infinity.ai"
        minSdk = 24
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        ndk {
            abiFilters += "arm64-v8a"
        }

        externalNativeBuild {
            cmake {
                cppFlags += "-std=c++17"
                arguments += listOf(
                    "-DANDROID_STL=c++_shared",
                    "-DANDROID_ARM_NEON=TRUE"
                )
            }
        }
    }

    ndkVersion = "28.2.13676358"

    // ── Tell Gradle where CMakeLists.txt lives ───────────────────────────────────
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    signingConfigs {
        if (hasReleaseKeystore) {
            create("release") {
                storeFile     = rootProject.file(keystoreProperties.getProperty("storeFile"))
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias      = keystoreProperties.getProperty("keyAlias")
                keyPassword   = keystoreProperties.getProperty("keyPassword")

                // The APK is ~1.1 GB because the GGUF model is bundled as an asset.
                // v1 (JAR) signing would digest every entry and rewrite MANIFEST.MF,
                // which is extremely slow at this size. v2/v3 sign the archive as a
                // whole and are supported from API 24, which is our minSdk — so v1
                // buys nothing here.
                enableV1Signing = false
                enableV2Signing = true
                enableV3Signing = true
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig =
                if (hasReleaseKeystore) signingConfigs.getByName("release") else null
        }
        debug {
            // Keep debug symbols for native crash debugging
            isJniDebuggable = true
        }
    }

    compileOptions {
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    kotlinOptions {
        jvmTarget = "11"
    }

    buildFeatures {
        compose = true
    }

    // ── Unit tests ─────────────────────────────────────────────────────────────
    testOptions {
        unitTests {
            // Lets unit tests read android.util.Log etc. without an emulator instead
            // of throwing "not mocked". The health domain is pure Kotlin and needs
            // none of this, but the shared test source set does touch Android types.
            isReturnDefaultValues = true
        }
    }

    // ── Asset packaging: allow large files (the GGUF model is ~1GB) ─────────────
    // By default Android compresses assets. We must disable compression for
    // large binary files — compressed assets can't be memory-mapped efficiently.
    androidResources {
        noCompress += listOf("gguf", "bin", "model")
    }

    // ── Packaging: include native .so files, exclude duplicates ─────────────────
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
        jniLibs {
            // Keep debug symbols in debug builds for crash analysis
            keepDebugSymbols += "**/*.so"
        }
    }

    androidResources {
        noCompress += "gguf"
    }
}

// ── Room schema export ─────────────────────────────────────────────────────────
// exportSchema = true needs a destination. The generated JSON under app/schemas/
// is the canonical record of each version and should be committed — it is what
// makes future migrations reviewable and testable.
ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    implementation(libs.androidx.core.ktx)
    coreLibraryDesugaring(libs.desugar.jdk.libs)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.material.icons.extended)
    implementation(libs.androidx.appcompat)
    implementation(libs.mlkit.text.recognition)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    implementation(libs.lottie.compose)
    implementation("com.github.PhilJay:MPAndroidChart:v3.1.0")
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.room.testing)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.ui.test.junit4)
    debugImplementation(libs.androidx.ui.tooling)
    debugImplementation(libs.androidx.ui.test.manifest)
}

// ── Fail loudly, not silently, on a missing release key ───────────────────────
// An unsigned release APK looks like a successful build and only fails much later
// at install time with a confusing INSTALL_PARSE_FAILED_NO_CERTIFICATES.
if (!hasReleaseKeystore) {
    gradle.taskGraph.whenReady {
        if (allTasks.any { it.name.contains("Release", ignoreCase = true) }) {
            logger.warn(
                """
                ============================================================
                WARNING: no keystore.properties found at the repo root.
                The release APK will be UNSIGNED and cannot be installed.
                Create keystore.properties with:
                    storeFile=gone-release.jks
                    storePassword=...
                    keyAlias=gone
                    keyPassword=...
                ============================================================
                """.trimIndent()
            )
        }
    }
}
