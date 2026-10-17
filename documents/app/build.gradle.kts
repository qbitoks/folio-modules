plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// Folio Документы: MJ PDF's engine (Pdfium, PDFView, text mode by Mudlej) under a Folio-style Compose UI. GPL-3.0.
android {
    namespace = "app.folio.documents"
    compileSdk = 37

    defaultConfig {
        applicationId = "app.folio.documents"
        minSdk = 34
        targetSdk = 37
        versionCode = 1
        versionName = "1.0.0"
        // Pdfium per ABI: phones (arm64) and the x86_64 emulator only, like the speech engines.
        ndk { abiFilters += listOf("arm64-v8a", "x86_64") }
    }

    // Signed like Folio: Folio installs and opens only modules that carry its own certificate.
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
    implementation(project(":documents:viewer"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.kotlinx.coroutines.android)
    testImplementation(libs.junit)
}
