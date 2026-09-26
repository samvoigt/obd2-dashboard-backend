plugins {
    alias(libs.plugins.kotlin.jvm)
}

kotlin {
    jvmToolchain(17)
    explicitApi()
}

dependencies {
    api(project(":registry"))
    api(libs.google.cloud.firestore)

    testImplementation(libs.junit)
    testImplementation(libs.kotest.assertions)
    testImplementation(libs.kotlinx.coroutines.test)
}

/**
 * The smoke test against the real database: run by hand, through
 * `scripts/firestore-smoke.sh`, never by `test`. It has no `@Test`, so the
 * test task cannot pick it up.
 */
tasks.register<JavaExec>("smoke") {
    description = "Round-trips a throwaway car through the real Firestore."
    group = "verification"
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("com.obd2dashboard.backend.registry.firestore.FirestoreSmokeKt")
    args(providers.gradleProperty("gcpProject").orNull ?: "")
    // Every run is against live state; never reuse a result.
    outputs.upToDateWhen { false }
}
