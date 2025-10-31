// file: whisper/build.gradle.kts
// ============================================================
// ✅ whisper.cpp JNI Library Module — Gradle 8.13 / Kotlin 2.2 Compatible
// ------------------------------------------------------------
// • Uses compilerOptions DSL
// • Uses correct externalNativeBuild CMake args under defaultConfig
// • Fully compatible with AGP 8.13.0 and Kotlin 2.2.20
// ============================================================

import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.whispercpp"
    compileSdk = 36

    defaultConfig {
        minSdk = 26

        // ABI filter
        ndk {
            abiFilters += listOf("arm64-v8a")
        }

        consumerProguardFiles("consumer-rules.pro")

        // ✅ Correct location for CMake arguments (under defaultConfig)
        @Suppress("UnstableApiUsage")
        externalNativeBuild {
            cmake {
                arguments(
                    "-DANDROID_STL=c++_shared",
                    project.findProperty("GGML_HOME")?.let { "-DGGML_HOME=$it" } ?: "-DGGML_HOME="
                )
            }
        }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
            buildConfigField("boolean", "JNI_DEBUG", "true")
        }
        release {
            isMinifyEnabled = false
            buildConfigField("boolean", "JNI_DEBUG", "false")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

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

    ndkVersion = "28.1.13356709"

    sourceSets {
        getByName("main") {
            java.srcDirs("src/main/java", "src/main/kotlin")
            jniLibs.srcDirs("src/main/jniLibs")
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/jni/whisper/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    buildFeatures {
        buildConfig = true
    }

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
        jniLibs.pickFirsts += listOf("**/libc++_shared.so")
    }

    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}
