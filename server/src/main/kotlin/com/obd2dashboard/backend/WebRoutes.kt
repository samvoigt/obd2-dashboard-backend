package com.obd2dashboard.backend

import io.ktor.http.CacheControl
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.http.content.staticResources
import io.ktor.server.response.cacheControl
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.RoutingCall
import io.ktor.server.routing.get

/**
 * The website (decision 13), from the jar's `web/`.
 *
 * **Explicit routes, not a single-page-app fallback**: a fallback would answer
 * an unknown `/api/...` with HTML instead of a `404`. Only `/`, a car's page, its
 * sessions and each session's page, and `/admin` get `index.html`; `/assets` holds Vite's hashed files, cached for a year.
 */
fun Route.webRoutes() {
    val index: String? = Thread.currentThread().contextClassLoader.getResource("web/index.html")?.readText()

    suspend fun RoutingCall.page() {
        if (index == null) {
            respond(HttpStatusCode.NotFound, ApiError("no_site", "The website was not built into this server (-PskipWeb)."))
            return
        }
        response.cacheControl(CacheControl.NoCache(null)) // always the latest page, which names the latest assets
        respondText(index, ContentType.Text.Html)
    }

    get("/") { call.page() }
    // Courses (M12.5), public.
    for (path in listOf("/courses", "/courses/", "/courses/{id}", "/courses/{id}/")) get(path) { call.page() }
    // Events and their results (M14.5), public.
    for (path in listOf("/events", "/events/", "/events/{id}", "/events/{id}/")) get(path) { call.page() }
    // Drivers (M15.5), public.
    for (path in listOf("/drivers", "/drivers/", "/drivers/{id}", "/drivers/{id}/")) get(path) { call.page() }
    // Two laps compared (M16.4), public.
    for (path in listOf("/compare", "/compare/")) get(path) { call.page() }
    get("/cars/{slug}") { call.page() }
    get("/cars/{slug}/") { call.page() }
    // Past sessions (M7.4, M7.5).
    for (path in listOf("/cars/{slug}/sessions", "/cars/{slug}/sessions/", "/cars/{slug}/sessions/{id}", "/cars/{slug}/sessions/{id}/")) {
        get(path) { call.page() }
    }
    // The admin page (M6.5) can replace every token, so no other site may frame it.
    for (path in listOf(
        "/admin", "/admin/", "/admin/courses", "/admin/courses/", "/admin/courses/{id}",
        "/admin/drivers", "/admin/drivers/", "/admin/events", "/admin/events/", "/admin/events/{id}",
    )) {
        get(path) {
            call.response.header("X-Frame-Options", "DENY")
            call.response.header("Content-Security-Policy", "frame-ancestors 'none'")
            call.page()
        }
    }
    // At the root because that's where they're asked for (M11): an iPhone's home-screen
    // icon, under both its names, and a classic favicon. Named, never a fallback.
    for ((path, file, type) in ROOT_ICONS) {
        val bytes = Thread.currentThread().contextClassLoader.getResource("web/$file")?.readBytes()
        get(path) {
            if (bytes == null) return@get call.respond(HttpStatusCode.NotFound, ApiError("not_found", "No such file."))
            call.response.cacheControl(CacheControl.MaxAge(maxAgeSeconds = 86_400, visibility = CacheControl.Visibility.Public))
            call.respondBytes(bytes, type)
        }
    }
    staticResources("/assets", "web/assets") {
        cacheControl { listOf(CacheControl.MaxAge(maxAgeSeconds = 31_536_000, visibility = CacheControl.Visibility.Public)) }
    }
}

/** The icons served at the root: path, file in the jar's `web/`, type. */
private val ROOT_ICONS = listOf(
    Triple("/apple-touch-icon.png", "apple-touch-icon.png", ContentType.Image.PNG),
    Triple("/apple-touch-icon-precomposed.png", "apple-touch-icon.png", ContentType.Image.PNG),
    Triple("/favicon.ico", "favicon.ico", ContentType.parse("image/x-icon")),
)
