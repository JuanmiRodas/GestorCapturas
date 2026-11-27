plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.example.screenshotmanager"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.example.screenshotmanager"
        minSdk = 30
        targetSdk = 35
        versionCode = 17
        versionName = "2.6"

        // ML Kit bundles native code; only 64-bit ARM phones are targeted, which keeps the APK small.
        ndk {
            abiFilters += "arm64-v8a"
        }
    }

    // All builds, local and on GitHub, must be signed with the same key or Android refuses to update
    // the installed app. CI points SIGNING_KEYSTORE at the key restored from the repository secret.
    signingConfigs {
        create("shared") {
            storeFile = file(System.getenv("SIGNING_KEYSTORE") ?: "${System.getProperty("user.home")}/.android/debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        getByName("debug") {
            signingConfig = signingConfigs.getByName("shared")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.activity:activity-ktx:1.9.3")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.preference:preference-ktx:1.2.1")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.work:work-runtime-ktx:2.9.1")
    implementation("com.google.android.gms:play-services-mlkit-text-recognition:19.0.1")
    implementation("com.google.android.gms:play-services-mlkit-image-labeling:16.0.8")
    implementation("com.google.mlkit:translate:17.0.3")
    implementation("com.google.mlkit:genai-image-description:1.0.0-beta1")
}
