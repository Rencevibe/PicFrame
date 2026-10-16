// Kotlin is compiled by AGP 9's built-in Kotlin support, so org.jetbrains.kotlin.android is not applied.
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.compiler)
}

android {
    // Placeholder ID until D4 (plan §6); it can't change after a Play release
    namespace = "io.github.rencevibe.picframe"
    compileSdk = 37 // Compose 1.12.1 AAR metadata requires >= 37 (V5)

    defaultConfig {
        applicationId = "io.github.rencevibe.picframe"
        minSdk = 26 // provisional until D2 (plan §6, V1)
        targetSdk = 36 // Play requirement from 2026-08-31 (V1)
        versionCode = 1
        versionName = "0.0.1"
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
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
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.ui.tooling.preview)
    debugImplementation(libs.androidx.compose.ui.tooling)

    testImplementation(libs.junit)
}
