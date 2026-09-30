plugins {
    id("com.android.application")
}

android {
    namespace = "com.example.termuxlite"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.example.termuxlite"
        minSdk = 24
        // Keep targetSdk at 28 or lower if you later want to exec binaries from the app data
        // directory (Android 10+ blocks that for targetSdk >= 29). /system/bin/sh works either way.
        targetSdk = 28
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    lint {
        checkReleaseBuilds = false
        abortOnError = false
    }

    packaging {
        jniLibs.useLegacyPackaging = true
    }
}

dependencies {
    implementation(project(":terminal-view"))
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.core:core:1.13.1")
}
