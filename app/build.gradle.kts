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
        versionCode     = 8
        versionName     = "1.0.7"
        multiDexEnabled = true

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
        compose = true
    }

    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
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
}
