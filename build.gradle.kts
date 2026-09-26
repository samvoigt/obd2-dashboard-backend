plugins {
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.ktor) apply false
}

/**
 * Warnings fail the build, everywhere — the same rule as the app, for the same
 * reason: a cached build never reprints them, so a warning nobody is made to act
 * on is a warning nobody sees.
 */
subprojects {
    tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
        compilerOptions.allWarningsAsErrors.set(true)
    }
}
