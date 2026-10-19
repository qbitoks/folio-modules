plugins {
    alias(libs.plugins.android.library)
}

// DjVuLibre (GPL-2.0-or-later) through a small JNI bridge. The .so files in src/main/jniLibs come from
// native/build.sh (see documents/README.md).
android {
    namespace = "app.folio.documents.djvu"
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
