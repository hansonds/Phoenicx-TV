plugins {
    id("com.android.application")
}

val appVersion = rootProject.file("VERSION").readText().trim()
val versionParts = appVersion.split(".").map { it.toIntOrNull() ?: 0 }
val computedVersionCode = (versionParts.getOrElse(0) { 0 } * 10000) +
    (versionParts.getOrElse(1) { 0 } * 100) +
    versionParts.getOrElse(2) { 0 }


android {
    namespace = "com.hanson.phoenicxtv"
    // 37 is required by Media3 1.11.
    compileSdk = 37

    defaultConfig {
        applicationId = "com.hanson.phoenicxtv"
        minSdk = 23
        targetSdk = 35
        versionCode = computedVersionCode
        versionName = appVersion
        vectorDrawables.useSupportLibrary = false
        // FFmpeg native code: ARM only (Fire TV, Android TV boxes, phones). Drops the x86 emulator builds.
        ndk { abiFilters += listOf("armeabi-v7a", "arm64-v8a") }
    }

    signingConfigs {
        // Keystore kept in the repo so every build is signed the same way and updates install over each other.
        create("release") {
            storeFile = rootProject.file("signing/phoenicxtv.jks")
            storePassword = "phoenicxtv"
            keyAlias = "phoenicxtv"
            keyPassword = "phoenicxtv"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("release")
        }
    }

    androidResources {
        // English only: drops the translated strings every AndroidX library ships with.
        localeFilters += listOf("en")
    }

    buildFeatures {
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        resources {
            excludes += listOf(
                "META-INF/*.version",
                "META-INF/*.kotlin_module",
                "META-INF/versions/**",
                "kotlin/**",
                "**/*.kotlin_builtins",
                "DebugProbesKt.bin",
                "META-INF/LICENSE*",
                "META-INF/NOTICE*",
                "META-INF/DEPENDENCIES"
            )
        }
    }

    lint {
        abortOnError = false
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    // Only what is needed to list channels and play a live stream.
    implementation("androidx.recyclerview:recyclerview:1.4.0")
    implementation("androidx.media3:media3-exoplayer:1.11.1")
    implementation("androidx.media3:media3-exoplayer-hls:1.11.1")
    // FFmpeg audio decoder (LGPL build) for AC-3 / E-AC-3 / DTS / etc. when the device has no hardware decoder.
    implementation(files("libs/media3-decoder-ffmpeg-lgpl.aar"))
}
