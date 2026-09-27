plugins {
    alias(libs.plugins.kotlin.jvm)
}

kotlin {
    jvmToolchain(17)
    explicitApi()
}

dependencies {
    api(project(":courses"))

    testImplementation(libs.junit)
    testImplementation(libs.kotest.assertions)
}
