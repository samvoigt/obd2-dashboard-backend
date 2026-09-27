package com.obd2dashboard.backend.registry

/**
 * Why a registry operation was refused. Messages are written for a person: the
 * admin tool shows them as they are.
 */
public sealed class RegistryException(message: String) : Exception(message) {
    public class InvalidSlug(raw: String, reason: String) :
        RegistryException("\"$raw\" is not a valid slug: $reason")

    public class InvalidName(reason: String) : RegistryException("invalid name: $reason")

    public class CarExists(slug: Slug) : RegistryException("a car with slug \"$slug\" already exists")

    public class NoSuchCar(slug: Slug) : RegistryException("no car with slug \"$slug\"")

    public class PasscodeTooShort : RegistryException(
        "a passcode needs at least ${Passcodes.MIN_LENGTH} characters",
    )

    public class InvalidToken(reason: String) : RegistryException(reason)

    /** Another car already has that token, so a tablet using it would be ambiguous. */
    public class TokenInUse(slug: Slug) : RegistryException("that token is already $slug's; choose another")

    /**
     * Two cars share one token hash. It cannot happen by chance with 256-bit
     * tokens, so it means the store is corrupt, and answering with either car
     * would be a coin toss about whose data this is.
     */
    public class DuplicateToken(slugs: List<Slug>) :
        RegistryException("cars ${slugs.joinToString()} share a token hash; the registry is corrupt")
}
