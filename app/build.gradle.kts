import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.tapmacro.recorder"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.tapmacro.recorder"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
    }

    signingConfigs {
        // A fixed key that lives in the repo so every build (local or CI) is signed
        // the same way and new versions install over old ones without losing macros.
        // Fine for a personal sideloaded app; don't reuse it for anything you publish.
        create("shared") {
            storeFile = file("tapmacro.keystore")
            storePassword = "tapmacro"
            keyAlias = "tapmacro"
            keyPassword = "tapmacro"
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("shared")
        }
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("shared")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
    // Real org.json for JVM unit tests (Android's copy is a stub off-device).
    testImplementation("org.json:json:20240303")
}
