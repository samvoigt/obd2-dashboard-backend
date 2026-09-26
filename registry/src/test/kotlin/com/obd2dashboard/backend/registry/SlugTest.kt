package com.obd2dashboard.backend.registry

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.Test

class SlugTest {
    @Test
    fun `accepts lower-case letters, digits and hyphens starting with a letter`() {
        for (raw in listOf("yaris", "car-42", "ab", "a".repeat(Slug.MAX_LENGTH), "x9-")) {
            Slug.parse(raw).value shouldBe raw
        }
    }

    @Test
    fun `refuses the wrong length`() {
        Slug.check("a").shouldBeInstanceOf<SlugCheck.Refused>()
        Slug.check("").shouldBeInstanceOf<SlugCheck.Refused>()
        Slug.check("a".repeat(Slug.MAX_LENGTH + 1)).shouldBeInstanceOf<SlugCheck.Refused>()
    }

    @Test
    fun `refuses upper case, a leading digit or hyphen, and other characters`() {
        for (raw in listOf("Yaris", "42car", "-car", "car_42", "car 42", "car.42", "cär")) {
            Slug.check(raw).shouldBeInstanceOf<SlugCheck.Refused>()
        }
    }

    @Test
    fun `refuses the site's own path words`() {
        for (raw in Slug.RESERVED) {
            Slug.check(raw).shouldBeInstanceOf<SlugCheck.Refused>()
        }
    }

    @Test
    fun `parse says why it refused`() {
        val e = shouldThrow<RegistryException.InvalidSlug> { Slug.parse("api") }
        e.message shouldBe "\"api\" is not a valid slug: \"api\" is used by the site itself"
    }
}
