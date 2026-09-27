plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    jvmToolchain(17)
    explicitApi()
}

dependencies {
    api(project(":courses"))
    api(project(":archive"))

    testImplementation(libs.junit)
    testImplementation(libs.kotest.assertions)
}
