plugins {
    alias(libs.plugins.kotlin.jvm)
}

kotlin {
    jvmToolchain(17)
    explicitApi()
}

// The owner's operations that span cars, sessions and messages (M6.1), shared by
// the admin tool and the admin page so the two can never disagree. Pure Kotlin.
dependencies {
    api(project(":registry"))
    api(project(":live"))

    testImplementation(libs.junit)
    testImplementation(libs.kotest.assertions)
    testImplementation(libs.kotlinx.coroutines.test)
}
