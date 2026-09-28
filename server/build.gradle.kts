plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ktor)
}

kotlin {
    jvmToolchain(17)
}

application {
    mainClass.set("com.obd2dashboard.backend.ApplicationKt")
}

ktor {
    fatJar {
        archiveFileName.set("server.jar")
    }
}

dependencies {
    implementation(project(":registry-firestore"))
    implementation(project(":archive-gcp"))
    implementation(project(":live"))
    implementation(project(":admin"))
    implementation(project(":timing"))
    implementation(platform(libs.google.cloud.bom))
    implementation(libs.google.auth)
    implementation(libs.google.http.gson)
    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.netty)
    implementation(libs.ktor.server.content.negotiation)
    implementation(libs.ktor.server.auth)
    implementation(libs.ktor.server.websockets)
    implementation(libs.ktor.server.sse)
    implementation(libs.ktor.server.call.logging)
    implementation(libs.ktor.serialization.kotlinx.json)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.logback.classic)

    testImplementation(libs.ktor.server.test.host)
    testImplementation(testFixtures(project(":timing")))
    testImplementation(libs.ktor.client.content.negotiation)
    testImplementation(libs.junit)
    testImplementation(libs.kotest.assertions)
    testImplementation(libs.kotlinx.coroutines.test)
}

/**
 * The website (`web/`, decision 13) is built with npm and served from the jar
 * as `web/`. `-PskipWeb` leaves it out for Kotlin-only work; `-PwebDist=<dir>`
 * takes a prebuilt one (the Dockerfile's `node:24` stage). Server tests carry
 * their own stub page, so they never need npm.
 */
val webDir = rootProject.layout.projectDirectory.dir("web")
val skipWeb = providers.gradleProperty("skipWeb").isPresent
// Relative to the repository root, and checked: a wrong path must fail the build,
// not ship a server without its website.
val prebuiltWeb = providers.gradleProperty("webDist").orNull?.let { rootProject.layout.projectDirectory.dir(".").file(it).asFile }

val buildWeb = tasks.register<Exec>("buildWeb") {
    description = "Builds the website with npm (npm ci, npm run build)."
    group = "build"
    workingDir = webDir.asFile
    commandLine("sh", "-c", "npm ci --no-audit --no-fund && npm run build")
    inputs.dir(webDir.dir("src"))
    inputs.files(
        webDir.file("package.json"), webDir.file("package-lock.json"), webDir.file("index.html"),
        webDir.file("vite.config.ts"), webDir.file("svelte.config.js"), webDir.file("tsconfig.json"),
    )
    outputs.dir(webDir.dir("dist"))
}

tasks.processResources {
    when {
        prebuiltWeb != null -> {
            val dist = prebuiltWeb
            doFirst {
                check(dist.resolve("index.html").isFile) { "-PwebDist=$dist has no index.html; build the site first" }
            }
            from(dist) { into("web") }
        }
        !skipWeb -> {
            dependsOn(buildWeb)
            from(webDir.dir("dist")) { into("web") }
        }
    }
}

/**
 * `./gradlew :server:devServer`: the real module on in-memory stores, with the
 * real site, for looking at pages without the cloud. Its own source set, so the
 * test resources' stub page cannot shadow the site.
 */
val dev: SourceSet = sourceSets.create("dev") {
    compileClasspath += sourceSets.main.get().output + sourceSets.main.get().compileClasspath
    runtimeClasspath += output + compileClasspath + sourceSets.main.get().runtimeClasspath
}

tasks.register<JavaExec>("devServer") {
    description = "Runs the server on in-memory stores at http://localhost:8080, with one car (dev-car)."
    group = "application"
    classpath = dev.runtimeClasspath
    mainClass.set("com.obd2dashboard.backend.DevServerKt")
    workingDir = layout.projectDirectory.asFile
    systemProperty("devTokenFile", layout.buildDirectory.file("dev-token").get().asFile.path)
}

// Course tests read the NHMS seed: a changed seed must re-run them (M12.3).
tasks.test {
    inputs.dir("../courses/seed")
}

