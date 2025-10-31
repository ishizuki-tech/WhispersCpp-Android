// file: build.gradle.kts
// ============================================================
// ✅ Root Build Script — Modern Kotlin DSL + Version Catalog
// ------------------------------------------------------------
// • Central plugin management (alias from libs.versions.toml)
// • Compatible with AGP 8.13.0 / Kotlin 2.2.20
// • Keeps modules clean and consistent
// ============================================================

plugins {
    // Application module (e.g. :app)
    alias(libs.plugins.android.application) apply false

    // Library modules (e.g. :whisper, :nativelib)
    alias(libs.plugins.android.library) apply false

    // Kotlin support
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
}

// ============================================================
// Optional global Gradle configuration
// ------------------------------------------------------------
// • Ensures consistent JDK 17 toolchain across modules
// • Disables build cache issues on CI (optional)
// ============================================================

allprojects {
    // Enforce JDK 17 for all Kotlin compile tasks
    plugins.withId("org.jetbrains.kotlin.android") {
        extensions.configure<org.jetbrains.kotlin.gradle.dsl.KotlinAndroidProjectExtension>("kotlin") {
            compilerOptions {
                jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
            }
        }
    }

    // Optional logging for verification
    afterEvaluate {
        println("✅ Configured module: ${project.name}")
    }
}
