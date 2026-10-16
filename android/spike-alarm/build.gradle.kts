// M1 throwaway spike (plan §7). Not part of the app; delete after the Go/No-go is recorded.
plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "io.github.rencevibe.picframe.spike"
    compileSdk = 37

    defaultConfig {
        applicationId = "io.github.rencevibe.picframe.spike"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "m1-spike"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
