plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    `java-test-fixtures`
}

kotlin {
    jvmToolchain(17)
    explicitApi()
}

dependencies {
    api(project(":courses"))
    api(project(":archive"))

    // A box course and logs of a car round it, for this module's tests and the server's (M13).
    testFixturesApi(project(":courses"))
    testFixturesApi(project(":archive"))

    testImplementation(libs.junit)
    testImplementation(libs.kotest.assertions)
}
