plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "app.kamera"
    compileSdk = 36

    defaultConfig {
        applicationId = "app.kamera"
        minSdk = 30
        targetSdk = 36
        versionCode = (System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 1)
        versionName = "1.0.${System.getenv("GITHUB_RUN_NUMBER") ?: "0"}"
    }

    signingConfigs {
        // Sideloaded builds need one stable key, or Android refuses to install updates over each
        // other. See "Signing" in the README before distributing the app beyond your own devices.
        create("sideload") {
            storeFile = file(System.getenv("KAMERA_KEYSTORE_FILE") ?: "kamera-sideload.keystore")
            storePassword = System.getenv("KAMERA_KEYSTORE_PASSWORD") ?: "kamera-sideload"
            keyAlias = System.getenv("KAMERA_KEY_ALIAS") ?: "kamera"
            keyPassword = System.getenv("KAMERA_KEY_PASSWORD") ?: "kamera-sideload"
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("sideload")
        }
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("sideload")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    lint {
        checkReleaseBuilds = false
    }

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(project(":core"))
}
