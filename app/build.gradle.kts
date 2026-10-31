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

    val keystoreFile = System.getenv("KEYSTORE_FILE")?.takeIf { it.isNotBlank() }
    val keystorePassword = System.getenv("KEYSTORE_PASS")?.takeIf { it.isNotBlank() } ?: System.getenv("KEYSTORE_PASSWORD")?.takeIf { it.isNotBlank() }
    val keyAliasName = System.getenv("KEY_ALIAS")?.takeIf { it.isNotBlank() }
    val keyPasswordValue = System.getenv("KEY_PASS")?.takeIf { it.isNotBlank() } ?: System.getenv("KEY_PASSWORD")?.takeIf { it.isNotBlank() }
    val signingValues = listOf(keystoreFile, keystorePassword, keyAliasName, keyPasswordValue)
    val hasCompleteReleaseSigning = signingValues.all { it != null }

    val productionReleaseSigning = if (hasCompleteReleaseSigning) {
        signingConfigs.create("productionRelease") {
            storeFile = file(keystoreFile!!)
            storePassword = keystorePassword
            keyAlias = keyAliasName
            keyPassword = keyPasswordValue  // 严格注意赋值方向
        }
    } else { null }

    buildTypes {
        release {
            signingConfig = productionReleaseSigning

            // Enables code shrinking, obfuscation, and optimization.
            isMinifyEnabled = true

            // Enables resource shrinking.
            isShrinkResources = true

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

    testImplementation("junit:junit:4.13.2")
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

tasks.matching { it.name == "assembleRelease" }.configureEach {
    doLast {
        val rootReleaseDir = rootProject.layout.projectDirectory.dir("build/outputs/apk/release").asFile
        rootReleaseDir.mkdirs()
        val appReleaseDir = layout.buildDirectory.dir("outputs/apk/release").get().asFile
        appReleaseDir.listFiles()?.forEach { file ->
            if (file.name.endsWith("-release.apk")) {
                file.copyTo(File(rootReleaseDir, file.name), overwrite = true)
            }
        }
    }
}
