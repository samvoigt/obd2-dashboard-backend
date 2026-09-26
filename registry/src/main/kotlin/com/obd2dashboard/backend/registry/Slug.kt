package com.obd2dashboard.backend.registry

/**
 * A car's permanent name in URLs: `/cars/{slug}`.
 *
 * **Permanent because it is a URL.** A car's display name can change; its slug
 * cannot, or every link to it breaks. So the rules are strict up front: lower
 * case, letters, digits and hyphens, starting with a letter, 2–32 characters,
 * and not a word the site's own paths use.
 */
@JvmInline
public value class Slug private constructor(public val value: String) {
    override fun toString(): String = value

    public companion object {
        public const val MIN_LENGTH: Int = 2
        public const val MAX_LENGTH: Int = 32

        /** Path segments the site uses itself, so no car may take them. */
        public val RESERVED: Set<String> = setOf("api", "v1", "cars", "admin", "health", "static")

        private val SHAPE = Regex("[a-z][a-z0-9-]*")

        /** Checks [raw] against every rule, saying which one it breaks. */
        public fun check(raw: String): SlugCheck = when {
            raw.length < MIN_LENGTH -> SlugCheck.Refused("a slug needs at least $MIN_LENGTH characters")
            raw.length > MAX_LENGTH -> SlugCheck.Refused("a slug has at most $MAX_LENGTH characters")
            !SHAPE.matches(raw) -> SlugCheck.Refused(
                "a slug is lower-case letters, digits and hyphens, starting with a letter",
            )
            raw in RESERVED -> SlugCheck.Refused("\"$raw\" is used by the site itself")
            else -> SlugCheck.Ok(Slug(raw))
        }

        /** [raw] as a slug, or [RegistryException.InvalidSlug] saying why not. */
        public fun parse(raw: String): Slug = when (val check = check(raw)) {
            is SlugCheck.Ok -> check.slug
            is SlugCheck.Refused -> throw RegistryException.InvalidSlug(raw, check.reason)
        }
    }
}

/** The outcome of [Slug.check]. */
public sealed interface SlugCheck {
    public data class Ok(val slug: Slug) : SlugCheck
    public data class Refused(val reason: String) : SlugCheck
}
