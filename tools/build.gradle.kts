plugins {
    alias(libs.plugins.kotlin.jvm)
    application
}

kotlin {
    jvmToolchain(17)
}

application {
    mainClass.set("com.obd2dashboard.backend.tools.AdminKt")
    applicationName = "admin"
}

dependencies {
    implementation(project(":registry-firestore"))
    implementation(project(":archive-gcp"))
    implementation(project(":live"))
    implementation(project(":admin"))
    implementation(project(":timing"))
    implementation(libs.clikt)
    // The Firestore client logs through SLF4J; with no provider it prints three
    // warnings into every command's output. This tool is read by a person.
    runtimeOnly(libs.slf4j.nop)

    testImplementation(libs.junit)
    testImplementation(libs.kotest.assertions)
}

// import-course's test reads the NHMS seed: a changed seed must re-run it (M12.3).
tasks.test {
    inputs.dir("../courses/seed")
}
