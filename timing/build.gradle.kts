plugins {
    alias(libs.plugins.kotlin.jvm)
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
