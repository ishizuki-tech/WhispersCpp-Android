// file: settings.gradle.kts
// ============================================================
// ✅ Root Project Settings — Kotlin DSL + Gradle 8.13 Compatible
// ------------------------------------------------------------
// • Centralized repository and plugin management
// • Safe defaults for Android, Google, and MavenCentral
// • Explicit module includes for :app and :nativelib
// • Build scan + plugin sync debug messages for inspection
// ============================================================

pluginManagement {
    repositories {
        // --------------------------------------------------------
        // Official Android / Google artifacts
        // --------------------------------------------------------
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }

        // --------------------------------------------------------
        // MavenCentral — for Kotlin, serialization, etc.
        // --------------------------------------------------------
        mavenCentral()

        // --------------------------------------------------------
        // Gradle Plugin Portal — for Kotlin DSL / Compose plugins
        // --------------------------------------------------------
        gradlePluginPortal()
    }

    // ------------------------------------------------------------
    // Optional: force plugin resolution versions (for debugging)
    // ------------------------------------------------------------
    plugins {
        id("com.android.application") version "8.13.0" apply false
        id("com.android.library") version "8.13.0" apply false
        id("org.jetbrains.kotlin.android") version "2.2.20" apply false
        id("org.jetbrains.kotlin.plugin.compose") version "2.2.20" apply false
    }
}

dependencyResolutionManagement {
    // ------------------------------------------------------------
    // Prevent subprojects from declaring their own repositories
    // ------------------------------------------------------------
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)

    // ------------------------------------------------------------
    // Centralized repository list for all modules
    // ------------------------------------------------------------
    repositories {
        google()
        mavenCentral()
    }
}

// ============================================================
// Project identity and included modules
// ============================================================

rootProject.name = "Whispers"

// App module — main entrypoint
include(":app")

// Native JNI module for whisper.cpp bindings
include(":nativelib")

// Optional: future library modules (uncomment if added)
// include(":whisper")

// ============================================================
// Debug info for sync
// ------------------------------------------------------------
// Prints confirmation during Gradle sync for easier debugging
// ============================================================
gradle.beforeProject {
    println("🔧 Configuring project: ${project.name}")
}
gradle.afterProject {
    println("✅ Finished configuring: ${project.name}")
}
