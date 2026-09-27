plugins {
    alias(libs.plugins.kotlin.jvm)
}

kotlin {
    jvmToolchain(17)
    explicitApi()
}

dependencies {
    api(project(":archive"))
    api(project(":live"))
    api(project(":courses"))
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

tasks.register<JavaExec>("messageSmoke") {
    description = "Sends, updates, queries and deletes crew messages against the real Firestore."
    group = "verification"
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("com.obd2dashboard.backend.archive.gcp.MessageSmokeKt")
    args(providers.gradleProperty("gcpProject").orNull ?: "")
    outputs.upToDateWhen { false }
}

tasks.register<JavaExec>("courseSmoke") {
    description = "Saves, reads, versions and deletes a throwaway course against the real Firestore."
    group = "verification"
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("com.obd2dashboard.backend.archive.gcp.CourseSmokeKt")
    args(providers.gradleProperty("gcpProject").orNull ?: "")
    outputs.upToDateWhen { false }
}


// The course mapping test reads the NHMS seed: a changed seed must re-run it (M12.3).
tasks.test {
    inputs.dir("../courses/seed")
}
