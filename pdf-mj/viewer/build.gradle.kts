plugins {
    alias(libs.plugins.android.library)
}

// MJ PDF's AndroidPdfViewer (GPL-3.0, originally Apache-2.0 by Bartosz Schiller).
android {
    namespace = "com.github.barteksc.pdfviewer"
    compileSdk = 37

    defaultConfig {
        minSdk = 34
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation(libs.material)
    implementation(libs.androidx.core.ktx)
    api(project(":pdf-mj:pdfium"))
}
