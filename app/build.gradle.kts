plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val appVersion = rootProject.file("VERSION").readText().trim()
val versionParts = appVersion.split(".").map { it.toIntOrNull() ?: 0 }
val computedVersionCode = (versionParts.getOrElse(0) { 0 } * 10000) +
    (versionParts.getOrElse(1) { 0 } * 100) +
    versionParts.getOrElse(2) { 0 }

val envKeystoreFile = System.getenv("KEYSTORE_FILE")
val envKeystorePassword = System.getenv("KEYSTORE_PASSWORD")
val envKeyAlias = System.getenv("KEY_ALIAS")
val envKeyPassword = System.getenv("KEY_PASSWORD") ?: envKeystorePassword
val hasSigning = !envKeystoreFile.isNullOrBlank() && file(envKeystoreFile).exists() &&
    !envKeystorePassword.isNullOrBlank() && !envKeyAlias.isNullOrBlank()

android {
    namespace = "com.hanson.phoenicxtv"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.hanson.phoenicxtv"
        minSdk = 21
        targetSdk = 35
        versionCode = computedVersionCode
        versionName = appVersion
        // English only: drops the translated strings every AndroidX library ships with.
        resourceConfigurations.add("en")
        vectorDrawables.useSupportLibrary = false
    }

    signingConfigs {
        if (hasSigning) {
            create("release") {
                storeFile = file(envKeystoreFile!!)
                storePassword = envKeystorePassword
                keyAlias = envKeyAlias
                keyPassword = envKeyPassword
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (hasSigning) signingConfig = signingConfigs.getByName("release")
        }
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
    implementation("androidx.media3:media3-exoplayer:1.5.1")
    implementation("androidx.media3:media3-exoplayer-hls:1.5.1")
}
