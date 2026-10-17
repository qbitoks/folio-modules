plugins {
    alias(libs.plugins.android.library)
}

// MJ PDF's PdfiumAndroid (GPL-3.0). The .so files in src/main/jniLibs come from ../native (see pdf-mj/README.md).
android {
    namespace = "com.shockwave.pdfium"
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
