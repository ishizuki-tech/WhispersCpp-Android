// file: app/build.gradle.kts
// ============================================================
// ✅ Android App Module — Compose + Whisper.cpp + Asset Safe
// ------------------------------------------------------------
// • Kotlin 2.2.x + Java 17 alignment (Gradle 8.13+)
// • Ensures app/src/main/assets/models/** are packaged in APK
// ============================================================

import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.negi.whispers"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.negi.whispers"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    // ------------------------------------------------------------
    // ✅ Explicit asset sourceSets — ensure models included
    // ------------------------------------------------------------
    sourceSets {
        getByName("main") {
            assets.srcDirs("src/main/assets")
            println("✅ Asset dirs: " + assets.srcDirs())
        }
    }

    // ------------------------------------------------------------
    // ✅ Build Types
    // ------------------------------------------------------------
    buildTypes {
        debug {
            isDebuggable = true
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    // ------------------------------------------------------------
    // ✅ Java 17 / Kotlin 17 setup
    // ------------------------------------------------------------
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlin {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
            freeCompilerArgs.addAll(
                "-Xjvm-default=all",
                "-opt-in=kotlin.RequiresOptIn"
            )
        }
    }

    // ------------------------------------------------------------
    // ✅ Compose + Build Features
    // ------------------------------------------------------------
    buildFeatures {
        compose = true
        buildConfig = true
    }

    // ------------------------------------------------------------
    // ✅ Packaging settings (resources only)
    // ------------------------------------------------------------
    packaging {
        resources {
            excludes += listOf(
                "META-INF/LICENSE*",
                "META-INF/NOTICE*",
                "META-INF/*.kotlin_module"
            )
        }
    }

    // ------------------------------------------------------------
    // ✅ Lint & Tests
    // ------------------------------------------------------------
    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
    }
}

// ============================================================
// ✅ Dependencies — Compose, Whisper JNI, Core libs
// ============================================================
dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(project(":nativelib"))

    // Core AndroidX + Lifecycle
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)

    // Compose UI
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.runtime.saveable)

    // Material
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.material.icons.extended)

    // Kotlinx Serialization
    implementation(libs.kotlinx.serialization.json)

    // Test
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)

    // Debug
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}

// ============================================================
// ✅ Diagnostic Task — verify asset inclusion
// ============================================================
tasks.register("printAssets") {
    group = "diagnostic"
    description = "Print all assets included in APK"
    doLast {
        val assetsDir = project.file("src/main/assets")
        if (assetsDir.exists()) {
            println("📦 Assets under: ${assetsDir.absolutePath}")
            assetsDir.walkTopDown().forEach { f ->
                if (f.isFile) println("  - ${f.relativeTo(assetsDir)} (${f.length()} bytes)")
            }
        } else {
            println("⚠️ No assets directory found!")
        }
    }
}
