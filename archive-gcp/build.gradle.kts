plugins {
    alias(libs.plugins.kotlin.jvm)
}

kotlin {
    jvmToolchain(17)
    explicitApi()
}

dependencies {
    api(project(":archive"))
    api(platform(libs.google.cloud.bom))
    api(libs.google.cloud.firestore)
    api(libs.google.cloud.storage)

    testImplementation(project(":registry-firestore"))
    testImplementation(libs.junit)
    testImplementation(libs.kotest.assertions)
    testImplementation(libs.kotlinx.coroutines.test)
}

/**
 * The archive against the real bucket and database: run by hand, through
 * `scripts/archive-smoke.sh`, never by `test`.
 */
tasks.register<JavaExec>("smoke") {
    description = "Uploads, completes and deletes a throwaway session against real Cloud Storage and Firestore."
    group = "verification"
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("com.obd2dashboard.backend.archive.gcp.ArchiveSmokeKt")
    args(providers.gradleProperty("gcpProject").orNull ?: "", providers.gradleProperty("bucket").orNull ?: "")
    outputs.upToDateWhen { false }
}
