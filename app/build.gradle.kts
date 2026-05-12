import java.util.Properties

// Load keys from local.properties — never hardcoded in source
val localProps = Properties().also { props ->
    val f = rootProject.file("local.properties")
    if (f.exists()) props.load(f.inputStream())
}

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.hilt)
    id("org.jetbrains.kotlin.kapt") version "1.9.23"
}

android {
    namespace   = "com.kate.assistant"
    compileSdk  = 35
    ndkVersion  = "26.1.10909125"

    defaultConfig {
        applicationId   = "com.kate.assistant"
        minSdk          = 26
        targetSdk       = 34
        versionCode     = 10
        versionName     = "2.0.0"
        multiDexEnabled = true

        // Inject API keys into BuildConfig — readable in Kotlin, never in source
        buildConfigField("String", "DEEPGRAM_KEY_PRIMARY",
            "\"${localProps["DEEPGRAM_KEY_PRIMARY"] ?: System.getenv("DEEPGRAM_KEY_PRIMARY") ?: ""}\"")
        buildConfigField("String", "DEEPGRAM_KEY_FALLBACK",
            "\"${localProps["DEEPGRAM_KEY_FALLBACK"] ?: System.getenv("DEEPGRAM_KEY_FALLBACK") ?: ""}\"")
        buildConfigField("String", "CLAUDE_API_KEY",
            "\"${localProps["CLAUDE_API_KEY"] ?: System.getenv("CLAUDE_API_KEY") ?: ""}\"")

        externalNativeBuild {
            cmake {
                cppFlags("")
                arguments("-DANDROID_PLATFORM=android-26")
            }
        }
        ndk { abiFilters += listOf("armeabi-v7a", "arm64-v8a") }
    }

    externalNativeBuild {
        cmake {
            path    = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    signingConfigs {
        create("release") {
            storeFile      = file(System.getenv("KEYSTORE_PATH") ?: "kate.jks")
            storePassword  = System.getenv("KEY_STORE_PASSWORD") ?: ""
            keyAlias       = System.getenv("KEY_ALIAS") ?: ""
            keyPassword    = System.getenv("KEY_PASSWORD") ?: ""
            enableV1Signing = false
            enableV2Signing = true
            enableV3Signing = true
            enableV4Signing = true
        }
    }

    buildFeatures {
        compose     = true
        buildConfig = true      // required for BuildConfig fields above
    }

    packaging {
        jniLibs { useLegacyPackaging = true }
    }

    buildTypes {
        release {
            isMinifyEnabled   = true
            isShrinkResources = true
            signingConfig     = signingConfigs.getByName("release")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            isDebuggable  = true
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    composeOptions { kotlinCompilerExtensionVersion = "1.5.13" }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation(libs.vosk)
    implementation(libs.hilt.android)
    kapt(libs.hilt.compiler)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.activity.compose)
    implementation(libs.coroutines.android)
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    kapt(libs.room.compiler)
    implementation(libs.datastore.preferences)
    implementation(libs.core.ktx)
    implementation(libs.lifecycle.runtime.ktx)
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.multidex:multidex:2.0.1")
    implementation("org.tensorflow:tensorflow-lite:2.14.0")

    // ── Networking (Deepgram + Claude API) ────────────────────
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.0")

    // ── Google Play Billing (subscriptions) ───────────────────
    implementation("com.android.billingclient:billing-ktx:7.0.0")

    // ── Navigation (new screens) ──────────────────────────────
    implementation("androidx.navigation:navigation-compose:2.7.7")
}


