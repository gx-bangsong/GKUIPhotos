/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.lineageos.generatebp)
}

val keystorePath: String? = System.getenv("KEYSTORE_FILE")
val keystorePass: String? = System.getenv("KEYSTORE_PASS")
val keyAlias: String? = System.getenv("KEY_ALIAS")
val keyPass: String? = System.getenv("KEY_PASS")
val isCi: Boolean = System.getenv("CI") == "true"

val releaseKeystoreFile: File? = if (!keystorePath.isNullOrBlank()) {
    val f = file(keystorePath)
    if (f.exists()) f else null
} else {
    null
}

val hasReleaseSigning: Boolean = releaseKeystoreFile != null &&
        !keystorePass.isNullOrBlank() &&
        !keyAlias.isNullOrBlank() &&
        !keyPass.isNullOrBlank()

fun getGitVersionCode(): Int {
    return try {
        val process = ProcessBuilder("git", "rev-list", "--count", "HEAD").start()
        val output = process.inputStream.bufferedReader().readText().trim()
        val exitCode = process.waitFor()
        if (exitCode == 0 && output.isNotEmpty()) {
            output.toInt()
        } else {
            1
        }
    } catch (e: Exception) {
        1
    }
}

fun getAppVersionName(versionCode: Int): String {
    val envVersion = System.getenv("VERSION_NAME")
    if (!envVersion.isNullOrBlank()) {
        return envVersion.trim()
    }
    val dateFormat = SimpleDateFormat("yyyyMMdd", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }
    return "${dateFormat.format(Date())}.$versionCode"
}

val calculatedVersionCode = getGitVersionCode()
val calculatedVersionName = getAppVersionName(calculatedVersionCode)

android {
    namespace = "org.lineageos.glimpse"
    compileSdk = 36

    defaultConfig {
        applicationId = "org.lineageos.glimpse"
        minSdk = 30
        targetSdk = 36
        versionCode = calculatedVersionCode
        versionName = calculatedVersionName
    }

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = releaseKeystoreFile
                storePassword = keystorePass
                this.keyAlias = keyAlias
                keyPassword = keyPass
            }
        }
    }

    buildTypes {
        release {
            if (hasReleaseSigning) {
                signingConfig = signingConfigs.getByName("release")
            } else {
                signingConfig = signingConfigs.getByName("debug")
            }

            // Enables code shrinking, obfuscation, and optimization.
            isMinifyEnabled = true

            // Enables resource shrinking.
            isShrinkResources = true

            isDebuggable = false

            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }

        debug {
            // Append .dev to package name so we won't conflict with AOSP build.
            applicationIdSuffix = ".dev"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    lint {
        lintConfig = file("lint.xml")
    }
}

dependencies {
    implementation(libs.adobe.xmpcore)
    implementation(libs.androidx.activity)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.exifinterface)
    implementation(libs.androidx.fragment.ktx)
    implementation(libs.androidx.lifecycle.service)
    implementation(libs.androidx.media3.common.ktx)
    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.exoplayer.dash)
    implementation(libs.androidx.media3.exoplayer.hls)
    implementation(libs.androidx.media3.exoplayer.rtsp)
    implementation(libs.androidx.media3.exoplayer.smoothstreaming)
    implementation(libs.androidx.media3.ui)
    implementation(libs.androidx.navigation.fragment.ktx)
    implementation(libs.androidx.navigation.ui.ktx)
    implementation(libs.androidx.preference)
    implementation(libs.androidx.recyclerview)
    implementation(libs.androidx.recyclerview.selection)
    implementation(libs.androidx.viewpager2)
    implementation(libs.glide)
    implementation(libs.glide.okhttp3.integration)
    implementation(libs.material)
    implementation(libs.okhttp)
    implementation(libs.zoomimage.view.glide)
}

generateBp {
    targetSdk = android.defaultConfig.targetSdk!!
    minSdk = android.defaultConfig.minSdk!!
    versionCode = 1
    versionName = "1.0"
    availableInAOSP = { module ->
        when {
            module.group.startsWith("androidx") -> {
                // We provide our own androidx.media3
                !module.group.startsWith("androidx.media3")
            }

            module.group.startsWith("org.jetbrains") -> true
            module.group == "com.google.errorprone" -> true
            module.group == "com.google.guava" -> true
            else -> false
        }
    }
}

gradle.taskGraph.whenReady {
    if (isCi && !hasReleaseSigning) {
        val hasReleaseTask = allTasks.any {
            it.name.equals("assembleRelease", ignoreCase = true) ||
                    it.name.equals("bundleRelease", ignoreCase = true)
        }
        if (hasReleaseTask) {
            throw GradleException(
                "Release keystore environment variables (KEYSTORE_FILE, KEYSTORE_PASS, KEY_ALIAS, KEY_PASS) " +
                        "are missing or invalid in CI environment! Refusing to build unsigned or debug-signed release APK."
            )
        }
    }
}



