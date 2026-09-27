plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    application
}

kotlin {
    jvmToolchain(17)
}

application {
    mainClass.set("com.obd2dashboard.backend.replay.ReplayKt")
    applicationName = "replay"
}

// A client of the server, not a user of its code: it hashes, gzips and speaks
// HTTP on its own, so it checks the server rather than agreeing with it.
dependencies {
    implementation(libs.clikt)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)

    testImplementation(project(":server"))
    testImplementation(project(":archive"))
    testImplementation(project(":live"))
    testImplementation(project(":registry"))
    testImplementation(project(":courses"))
    testImplementation(libs.ktor.server.netty)
    testImplementation(libs.junit)
    testImplementation(libs.kotest.assertions)
    testImplementation(libs.kotlinx.coroutines.test)
}

// The --courses test reads the NHMS seed: a changed seed must re-run it (M12.6).
tasks.test {
    inputs.dir("../courses/seed")
}
