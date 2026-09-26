plugins {
    alias(libs.plugins.kotlin.jvm)
}

kotlin {
    jvmToolchain(17)
    explicitApi()
}

dependencies {
    api(libs.kotlinx.coroutines.core)
    // As a library only, to check that a line is a JSON object. Nothing is
    // decoded into a type, so nothing on the archive path can be re-encoded.
    implementation(libs.kotlinx.serialization.json)

    testImplementation(libs.junit)
    testImplementation(libs.kotest.assertions)
    testImplementation(libs.kotlinx.coroutines.test)
}
