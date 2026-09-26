# Architecture decisions

Each entry records what was decided, why, and what would make us revisit it.
**Don't reverse one silently — add a superseding entry.**

---

## 1. Kotlin + Ktor on the server

**Decision.** The backend is Kotlin on the JVM, using Ktor.

**Why.** It has two jobs: take in data from the tablet, and push live data to
browsers. Both are long-lived streaming connections, and Ktor supports WebSockets
and server-sent events with coroutines and `Flow`, which the app already uses for
its reading bus. The client is a Kotlin app, so whatever is sent between the
two — the session-log records, and later a live frame — can be written as one
`@Serializable` type used on both sides, instead of being kept in sync by hand.
Kotlin, kotlinx and JUnit/kotest versions track the app's for that reason.

**Alternatives weighed.** Python/FastAPI is quicker to start but gives up the
shared types, and its async model is a second one to learn next to coroutines.
Node/TypeScript would suit the website, but Node is not installed and the
ingest side gets nothing from it.

**Revisit if.** The website grows into a real single-page app. That affects
*where the frontend lives* (see decision 3), not the server language.

---

## 2. Google Cloud Run, from a Dockerfile

**Decision.** Deploy to Cloud Run. Cloud Build builds the `Dockerfile` using
`gcloud run deploy --source .`.

**Why.** It scales to zero between race weekends, which suits a car that is
driven a few days a month. It supports WebSockets, and it needs no Docker
install on the dev machine. The server reads `PORT` and binds `0.0.0.0`, as
Cloud Run requires.

**Cost.** A Cloud Run request, including a WebSocket, lasts at most 60 minutes, so
live connections must expect to reconnect. Scaling to zero also means live data
cannot be kept in one instance's memory while several instances run. Both matter
when the live feed is designed, not before.

**Revisit if.** A live session needs state shared across instances (use
Memorystore or Pub/Sub), or the cold start after scaling to zero turns out to be
too slow.

---

## 3. The website starts inside the server

**Decision.** For now the live website is served by the Ktor server, from the
same deployment.

**Why.** One deploy and one origin, with no CORS and nothing to decide before
there is a page to show. Splitting it out later is a move, not a rewrite.

**Revisit if.** The frontend needs its own build tool (React, Svelte, etc.). Then
pick a framework in a new decision, and decide whether it still ships from
the server.
