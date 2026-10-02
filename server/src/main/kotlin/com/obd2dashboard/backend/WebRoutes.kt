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
        // Every page can carry the admin's edits once signed in (M21), so no other site may frame any of them.
        response.header("X-Frame-Options", "DENY")
        response.header("Content-Security-Policy", "frame-ancestors 'none'")
        respondText(index, ContentType.Text.Html)
    }

    get("/") { call.page() }
    // Courses (M12.5), and their editor where they're shown (M21.3; `new` for one not yet saved).
    for (path in listOf("/courses", "/courses/", "/courses/{id}", "/courses/{id}/", "/courses/{id}/edit", "/courses/{id}/edit/")) get(path) { call.page() }
    // Events and their results (M14.5), and their editor the same way.
    for (path in listOf("/events", "/events/", "/events/{id}", "/events/{id}/", "/events/{id}/edit", "/events/{id}/edit/")) get(path) { call.page() }
    // Drivers (M15.5), public.
    for (path in listOf("/drivers", "/drivers/", "/drivers/{id}", "/drivers/{id}/")) get(path) { call.page() }
    // Two laps compared (M16.4), public.
    for (path in listOf("/compare", "/compare/")) get(path) { call.page() }
    // The invited users (M23), for a master admin; the page asks the admin API, which decides.
    for (path in listOf("/users", "/users/")) get(path) { call.page() }
    // The cars (M21.5), each one's live feed, and its management.
    for (path in listOf("/cars", "/cars/", "/cars/{slug}", "/cars/{slug}/", "/cars/{slug}/manage", "/cars/{slug}/manage/")) get(path) { call.page() }
    // Past sessions (M7.4, M7.5).
    for (path in listOf("/cars/{slug}/sessions", "/cars/{slug}/sessions/", "/cars/{slug}/sessions/{id}", "/cars/{slug}/sessions/{id}/")) {
        get(path) { call.page() }
    }
    // No admin page (M21): its edits are on the pages they belong to, once signed in.
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
