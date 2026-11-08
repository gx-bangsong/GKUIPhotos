/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

import java.io.File
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.lineageos.generatebp)
}

val isCi = System.getenv("CI")?.equals("true", ignoreCase = true) == true
val gitCommitCount = try {
    val process = ProcessBuilder("git", "rev-list", "--count", "HEAD")
        .directory(rootDir)
        .redirectErrorStream(true)
        .start()
    val output = process.inputStream.bufferedReader().use { it.readText() }.trim()
    if (process.waitFor() == 0) output.toIntOrNull() else null
} catch (_: Exception) {
    null
}
if (isCi && (gitCommitCount == null || gitCommitCount < 1)) {
    throw GradleException("CI requires a complete Git checkout to derive versionCode")
}
val buildVersionCode = gitCommitCount?.takeIf { it > 0 } ?: 1
val buildVersionName = System.getenv("VERSION_NAME")?.takeIf { it.isNotBlank() }
    ?: "${LocalDate.now(ZoneOffset.UTC).format(DateTimeFormatter.BASIC_ISO_DATE)}.$buildVersionCode"

val keystoreFile = System.getenv("KEYSTORE_FILE")?.takeIf { it.isNotBlank() }
val keystorePassword = System.getenv("KEYSTORE_PASS")?.takeIf { it.isNotBlank() }
val keyAliasName = System.getenv("KEY_ALIAS")?.takeIf { it.isNotBlank() }
val keyPasswordValue = System.getenv("KEY_PASS")?.takeIf { it.isNotBlank() }
val signingValues = listOf(keystoreFile, keystorePassword, keyAliasName, keyPasswordValue)
val hasCompleteReleaseSigning = signingValues.all { it != null }
val hasPartialReleaseSigning = signingValues.any { it != null } && !hasCompleteReleaseSigning

android {
    namespace = "org.lineageos.glimpse"
    compileSdk = 36

    defaultConfig {
        applicationId = "org.lineageos.glimpse"
        minSdk = 30
        targetSdk = 36
        versionCode = buildVersionCode
        versionName = buildVersionName
    }

    val productionReleaseSigning = if (hasCompleteReleaseSigning) {
        signingConfigs.create("productionRelease") {
            storeFile = file(keystoreFile!!)
            storePassword = keystorePassword
            keyAlias = keyAliasName
            keyPassword = keyPasswordValue
        }
    } else {
        null
    }

    buildTypes {
        getByName("release") {
            // A production release never inherits the debug package identity or debug flag.
            applicationIdSuffix = null
            versionNameSuffix = null
            isDebuggable = false
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

        getByName("debug") {
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

    testOptions {
        unitTests {
            isReturnDefaultValues = false
        }
    }
}

// Do not break ordinary CI/debug builds that do not need release credentials. As soon as a
// release task enters the graph, however, CI requires all production secrets. Local release
// builds without them remain unsigned (never debug-signed) and emit a prominent warning.
gradle.taskGraph.whenReady {
    val releaseRequested = allTasks.any { task ->
        task.name.contains("release", ignoreCase = true)
    }
    if (!releaseRequested) {
        return@whenReady
    }
    if (hasPartialReleaseSigning) {
        val message = "Release signing is incomplete; set KEYSTORE_FILE, KEYSTORE_PASS, " +
                "KEY_ALIAS, and KEY_PASS together"
        if (isCi) {
            throw GradleException(message)
        }
        logger.warn("WARNING: $message. The local release APK will be unsigned.")
    } else if (!hasCompleteReleaseSigning) {
        if (isCi) {
            throw GradleException("CI release builds require production signing credentials")
        }
        logger.warn(
            "WARNING: Production signing credentials are missing; " +
                    "the local release APK will be unsigned (never debug-signed)."
        )
    } else if (!file(keystoreFile!!).isFile) {
        throw GradleException("KEYSTORE_FILE does not exist or is not a file: $keystoreFile")
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
