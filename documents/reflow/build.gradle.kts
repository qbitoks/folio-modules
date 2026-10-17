plugins {
    alias(libs.plugins.android.library)
}

// k2pdfopt's page reflow (willus.com, KOReader's libk2pdfopt; AGPL-3.0) + Leptonica (BSD-2). The .so files in
// src/main/jniLibs come from native/build.sh (see documents/README.md).
android {
    namespace = "app.folio.documents.reflow"
    compileSdk = 37

    defaultConfig {
        minSdk = 34
        consumerProguardFiles("consumer-rules.pro")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
