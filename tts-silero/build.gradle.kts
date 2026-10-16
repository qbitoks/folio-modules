plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "app.folio.tts.silero"
    compileSdk = 37

    defaultConfig {
        applicationId = "app.folio.tts.silero"
        minSdk = 34
        targetSdk = 37
        versionCode = 1
        versionName = "1.0.0"
        // onnxruntime ships ~15 MB of native code per ABI: phones (arm64) and the x86_64 emulator only.
        ndk { abiFilters += listOf("arm64-v8a", "x86_64") }
    }

    // Signed like Folio: Folio installs only engine APKs that carry its own certificate. Tag CI restores the
    // shared preview keystore; local builds use this machine's debug key (the same one here).
    System.getenv("ANDROID_DEBUG_KEYSTORE_PATH")?.let { keyPath ->
        signingConfigs.getByName("debug") {
            storeFile = file(keyPath)
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    packaging { jniLibs { useLegacyPackaging = true } }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }
}

dependencies {
    implementation(project(":tts-common"))
    implementation(libs.onnxruntime.android)
    testImplementation(libs.junit)
}
