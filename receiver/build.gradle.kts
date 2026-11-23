plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.automotive.carmirror.receiver"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.automotive.carmirror.receiver"
        minSdk = 26
        targetSdk = 31 // Target específico para Allwinner T3L (Android 12 / API 31)
        versionCode = 11
        versionName = "2.0.0-T3L"

        // CRÍTICO para Allwinner T3L (ARM Cortex-A7 32-bit):
        // Garante compatibilidade com armeabi-v7a (32-bit) e arm64-v8a (64-bit)
        ndk {
            abiFilters += listOf("armeabi-v7a", "arm64-v8a")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.getByName("debug") // Substituir por keystore de produção se desejar
        }
        debug {
            isMinifyEnabled = false
        }
    }

    packaging {
        jniLibs {
            // Compatível com loaders 32-bit do firmware Allwinner V8.1.1
            useLegacyPackaging = true
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    lint {
        // Sideload automotivo dedicado para Android 12 (API 31) na Allwinner T3L
        disable += "ExpiredTargetSdkVersion"
    }
}

dependencies {
    // Footprint mínimo de RAM para placa T3L de 2.0 GB RAM + Okio Zero-Copy I/O
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("com.squareup.okio:okio:3.9.0")
}
