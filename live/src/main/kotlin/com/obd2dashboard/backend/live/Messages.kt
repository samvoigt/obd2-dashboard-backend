package com.obd2dashboard.backend.live

import java.security.SecureRandom
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Where a crew message stands (contract §5.4). **States only move forward**:
 * queued → received → displayed, and then it ends as cleared, expired or
 * replaced. `replaced` is the crew's word for a message a newer one took over;
 * on the wire the tablet simply shows the newer (§5.4).
 */
public enum class MessageState(public val wire: String, private val rank: Int) {
    Queued("queued", 0),
    Received("received", 1),
    Displayed("displayed", 2),
    Cleared("cleared", 3),
    Expired("expired", 3),
    Replaced("replaced", 3);

    public val active: Boolean get() = rank < 3

    /** Whether moving to [next] is forward. Once ended, nothing moves. */
    public fun canBecome(next: MessageState): Boolean = active && next.rank > rank
}

/** A message from the crew to one car's driver. Kept after it ends, as a record. */
public data class Message(
    val id: String,
    val car: String,
    val text: String,
    val preset: String?,
    val sentAt: Instant,
    val expiresAt: Instant,
    val state: MessageState,
    val receivedAt: Instant? = null,
    val displayedAt: Instant? = null,
    val endedAt: Instant? = null,
    val replacedBy: String? = null,
)

/**
 * Where messages are kept (Firestore in production, M5.2). Deliberately thin:
 * the rules are [Messages]', and [update] is an **atomic read-modify-write**, so
 * two instances during a deploy's changeover cannot undo each other.
 */
public interface MessageStore {
    public suspend fun create(message: Message)

    /** Applies [change] atomically; a null result means "no change". Returns the stored message after it. */
    public suspend fun update(id: String, change: (Message) -> Message?): Message?

    public suspend fun get(id: String): Message?

    /** Messages for [car] still in an active state (queued, received, displayed), expired or not. */
    public suspend fun active(car: String): List<Message>

    /** The most recent [limit] messages for [car], newest first. */
    public suspend fun recent(car: String, limit: Int): List<Message>
}

public class InMemoryMessageStore : MessageStore {
    private val messages = ConcurrentHashMap<String, Message>()

    override suspend fun create(message: Message) {
        check(messages.putIfAbsent(message.id, message) == null) { "message ${message.id} exists" }
    }

    override suspend fun update(id: String, change: (Message) -> Message?): Message? {
        var result: Message? = null
        messages.computeIfPresent(id) { _, current -> (change(current) ?: current).also { result = it } }
        return result
    }

    override suspend fun get(id: String): Message? = messages[id]

    override suspend fun active(car: String): List<Message> =
        messages.values.filter { it.car == car && it.state.active }

    override suspend fun recent(car: String, limit: Int): List<Message> =
        messages.values.filter { it.car == car }.sortedByDescending { it.sentAt }.take(limit)
}

/**
 * The crew-message rules (contract §5.4), on a [Clock]:
 * - **one active message per car**: sending replaces the one before;
 * - **forward-only** states, with anything for an unknown or ended id ignored;
 * - **expiry on the server's clock**, and an expired message is never offered
 *   to the tablet;
 * - time left (`ttlMs`) and age (`ageMs`) worked out when a frame is built,
 *   never as a wall-clock deadline for the tablet to compare.
 */
public class Messages(
    private val store: MessageStore,
    private val clock: Clock = Clock.systemUTC(),
    private val random: SecureRandom = SecureRandom(),
) {
    private val locks = ConcurrentHashMap<String, Mutex>()

    /** What a send did: the new message, and the one it replaced, if any. */
    public data class Sent(val message: Message, val replaced: Message?)

    public sealed interface Checked {
        public data class Ok(val text: String, val preset: String?, val ttl: Duration) : Checked
        public data class Bad(val reason: String) : Checked
    }

    public suspend fun send(car: String, text: String, preset: String?, ttl: Duration = DEFAULT_TTL): Sent =
        locks.computeIfAbsent(car) { Mutex() }.withLock {
            val checked = when (val c = check(text, preset, ttl)) {
                is Checked.Ok -> c
                is Checked.Bad -> throw IllegalArgumentException(c.reason)
            }
            val now = clock.instant()
            val id = "m_" + (1..8).joinToString("") { "%02x".format(random.nextInt(256)) }
            expireDue(car) // one that has run out is expired, not replaced
            val replaced = store.active(car).mapNotNull { old ->
                store.update(old.id) {
                    if (it.state.canBecome(MessageState.Replaced)) {
                        it.copy(state = MessageState.Replaced, endedAt = now, replacedBy = id)
                    } else {
                        null
                    }
                }?.takeIf { it.replacedBy == id }
            }.lastOrNull()
            val message = Message(id, car, checked.text, checked.preset, now, now.plus(checked.ttl), MessageState.Queued)
            store.create(message)
            Sent(message, replaced)
        }

    /** The tablet has it (§5.4). Null if nothing changed: an unknown, ended or repeated `received`. */
    public suspend fun received(id: String): Message? = advance(id, MessageState.Received) { copy(receivedAt = it) }

    /** A widget is drawing it (§5.4). Null if nothing changed. */
    public suspend fun displayed(id: String): Message? = advance(id, MessageState.Displayed) { copy(displayedAt = it) }

    /** The crew took it down. Null if it was not active. */
    public suspend fun clear(id: String): Message? = advance(id, MessageState.Cleared) { copy(endedAt = it) }

    /** Marks [car]'s due messages expired, returning those it changed. */
    public suspend fun expireDue(car: String): List<Message> {
        val now = clock.instant()
        return store.active(car).filter { !it.expiresAt.isAfter(now) }.mapNotNull { due ->
            var changed = false
            store.update(due.id) {
                if (it.state.canBecome(MessageState.Expired) && !it.expiresAt.isAfter(now)) {
                    changed = true
                    it.copy(state = MessageState.Expired, endedAt = now)
                } else {
                    null
                }
            }?.takeIf { changed }
        }
    }

    /** What the tablet should be showing now: never an expired message. */
    public suspend fun active(car: String): List<Message> {
        val now = clock.instant()
        return store.active(car).filter { it.expiresAt.isAfter(now) }.sortedBy { it.sentAt }
    }

    public suspend fun recent(car: String, limit: Int = 20): List<Message> = store.recent(car, limit)

    public suspend fun get(id: String): Message? = store.get(id)

    /** A `message` frame's body, or an `active` item: `id`, `text`, `preset?`, `ageMs`, `ttlMs`, as the app reads them. */
    public fun wire(message: Message, now: Instant = clock.instant()): JsonObject = buildJsonObject {
        put("id", message.id)
        put("text", message.text)
        message.preset?.let { put("preset", it) }
        put("ageMs", Duration.between(message.sentAt, now).toMillis().coerceAtLeast(0))
        put("ttlMs", Duration.between(now, message.expiresAt).toMillis().coerceAtLeast(0))
    }

    /** `{"t":"message",…}` for one just sent (§5.2). */
    public fun messageFrame(message: Message): String = buildJsonObject {
        put("t", "message")
        wire(message).forEach { (k, v) -> put(k, v) }
    }.toString()

    /** `{"t":"clear","id":…}` (§5.2). */
    public fun clearFrame(id: String): String = buildJsonObject { put("t", "clear"); put("id", id) }.toString()

    /** `{"t":"messages","active":[…]}`, the complete active set sent after every `hello` (§5.4). */
    public suspend fun syncFrame(car: String): String {
        val now = clock.instant()
        val active = active(car)
        return buildJsonObject {
            put("t", "messages")
            put("active", buildJsonArray { active.forEach { add(wire(it, now)) } })
        }.toString()
    }

    private suspend fun advance(id: String, next: MessageState, stamp: Message.(Instant) -> Message): Message? {
        val now = clock.instant()
        var changed = false
        val result = store.update(id) {
            // An expired message may not be revived by a late report, even before expireDue has run.
            if (it.state.canBecome(next) && (next == MessageState.Cleared || it.expiresAt.isAfter(now))) {
                changed = true
                it.copy(state = next).stamp(now)
            } else {
                null
            }
        }
        return result.takeIf { changed }
    }

    public companion object {
        /** Contract §5.4, and the website: at most 40 characters. */
        public const val MAX_TEXT: Int = 40
        /** The contract's presets (§5.4); the tablet styles by them, and shows the text whatever. */
        public val PRESETS: Set<String> = setOf("pit", "box", "fuel", "push", "slow")
        /** Sam: up until cleared, capped at 30 minutes. */
        public val DEFAULT_TTL: Duration = Duration.ofMinutes(30)
        public val MIN_TTL: Duration = Duration.ofMinutes(1)
        public val MAX_TTL: Duration = Duration.ofMinutes(30)

        /** The text trimmed and 1–40 characters, a known preset or none, and a lifetime within bounds. */
        public fun check(text: String, preset: String?, ttl: Duration): Checked {
            val trimmed = text.trim()
            if (trimmed.isEmpty()) return Checked.Bad("a message needs some text")
            if (trimmed.codePointCount(0, trimmed.length) > MAX_TEXT) return Checked.Bad("a message is at most $MAX_TEXT characters")
            if (preset != null && preset !in PRESETS) return Checked.Bad("unknown preset \"$preset\"")
            if (ttl < MIN_TTL || ttl > MAX_TTL) return Checked.Bad("a message lasts between 1 and 30 minutes")
            return Checked.Ok(trimmed, preset, ttl)
        }
    }
}
