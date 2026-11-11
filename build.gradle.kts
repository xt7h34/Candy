import java.util.Base64

plugins {
    id("com.android.application") version "8.7.3"
    id("org.jetbrains.kotlin.android") version "2.0.21"
    id("org.jetbrains.kotlin.plugin.compose") version "2.0.21"
    id("com.google.gms.google-services") version "4.4.2" apply false
}

// Notifications need google-services.json (from the Firebase console, package com.candy) next to this file.
// Without it the app still builds and works, just with no notifications.
if (File(rootDir, "google-services.json").exists()) {
    apply(plugin = "com.google.gms.google-services")
}

// The icon and the signing key are stored as text (so they can be pasted from a phone)
// and decoded back into real files here, at build time.
fun decodeBase64File(root: File, source: String, target: File) {
    val src = File(root, source)
    if (src.exists()) {
        target.parentFile.mkdirs()
        target.writeBytes(Base64.getMimeDecoder().decode(src.readText().trim()))
    }
}

val debugKeystoreFile = File(layout.buildDirectory.get().asFile, "debug.keystore")
decodeBase64File(rootDir, "debug.keystore.b64", debugKeystoreFile)
decodeBase64File(rootDir, "icon.b64", File(rootDir, "src/main/res/drawable-nodpi/ic_launcher_foreground.webp"))

android {
    namespace = "com.candy"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.candy"
        minSdk = 26
        targetSdk = 35
        versionCode = 28
        versionName = "1.28"
        // Where the app looks for newer versions. The build passes it in (-PupdateRepo=owner/name).
        buildConfigField("String", "UPDATE_REPO", "\"${project.findProperty("updateRepo") ?: ""}\"")
        // Public address of the Kanade bot (the tunnel), one line in api-url.txt at the repo root.
        // Missing or empty = the bot on this same phone.
        val apiUrl = File(rootDir, "api-url.txt").takeIf { it.exists() }?.readLines()?.firstOrNull()?.trim().orEmpty().replace("\"", "")
        buildConfigField("String", "PUBLIC_BASE", "\"$apiUrl\"")
    }

    // Fixed debug key: every build is signed the same way, so a new APK installs over the old one.
    signingConfigs {
        getByName("debug") {
            if (debugKeystoreFile.exists()) {
                storeFile = debugKeystoreFile
                storePassword = "android"
                keyAlias = "androiddebugkey"
                keyPassword = "android"
            }
        }
    }

    // The release build is the one people install: smaller and noticeably smoother than the debug one (Compose is much slower
    // when it is not optimized). It is signed with the same fixed key as before, so it installs over the previous version.
    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfig = signingConfigs.getByName("debug")
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.animation:animation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    // camera: preview, photos and short videos
    implementation("androidx.camera:camera-core:1.3.4")
    implementation("androidx.camera:camera-camera2:1.3.4")
    implementation("androidx.camera:camera-lifecycle:1.3.4")
    implementation("androidx.camera:camera-video:1.3.4")
    implementation("androidx.camera:camera-view:1.3.4")
    // notifications for new messages
    implementation(platform("com.google.firebase:firebase-bom:33.7.0"))
    implementation("com.google.firebase:firebase-messaging")
    // video that plays while it downloads, with a cache on the phone (loops and stories)
    implementation("androidx.media3:media3-exoplayer:1.5.1")
    implementation("androidx.media3:media3-ui:1.5.1")
    // makes a video lighter on the phone before it is sent
    implementation("androidx.media3:media3-transformer:1.5.1")
    implementation("androidx.media3:media3-effect:1.5.1")
}
