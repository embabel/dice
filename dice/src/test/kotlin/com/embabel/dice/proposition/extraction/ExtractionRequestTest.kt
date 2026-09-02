/*
 * Copyright 2024-2026 Embabel Pty Ltd.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.embabel.dice.proposition.extraction

import com.embabel.dice.provenance.ContentAddressedLocator
import com.embabel.dice.provenance.SourceRevisionRef
import com.embabel.dice.provenance.UriLocator
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatIllegalArgumentException
import org.junit.jupiter.api.Test

/**
 * What the request promises the entry points: the pairing rule holds wherever a request is built,
 * a profile is coupled to nothing, and an empty request means plain extraction.
 */
class ExtractionRequestTest {

    private val locator = UriLocator("https://example.com/source")
    private val revision = SourceRevisionRef(locator.key(), "r1")
    private val profile = ExtractionContentProfileRef("house-style", "v1")

    @Test
    fun `an empty request carries nothing and equals NONE`() {
        val empty = ExtractionRequest()

        assertThat(empty.sourceLocator).isNull()
        assertThat(empty.sourceRevision).isNull()
        assertThat(empty.profile).isNull()
        assertThat(empty).isEqualTo(ExtractionRequest.NONE)
        assertThat(empty.isEmpty).isTrue()
    }

    @Test
    fun `any field at all makes a request non-empty`() {
        // isEmpty is the switch the file entry point routes on, so each field has to flip it.
        assertThat(ExtractionRequest(sourceLocator = locator).isEmpty).isFalse()
        assertThat(
            ExtractionRequest(sourceLocator = locator, sourceRevision = revision).isEmpty,
        ).isFalse()
        assertThat(ExtractionRequest(profile = profile).isEmpty).isFalse()
    }

    @Test
    fun `a request keeps exactly what it was given`() {
        val request = ExtractionRequest(
            sourceLocator = locator,
            sourceRevision = revision,
            profile = profile,
        )

        assertThat(request.sourceLocator).isSameAs(locator)
        assertThat(request.sourceRevision).isSameAs(revision)
        assertThat(request.profile).isSameAs(profile)
    }

    @Test
    fun `a revision requires a locator`() {
        assertThatIllegalArgumentException()
            .isThrownBy { ExtractionRequest(sourceRevision = revision) }
            .withMessageContaining("sourceLocator is required")

        // Same rule through the copy helper, which is where a caller assembling a request
        // incrementally would otherwise slip past it.
        assertThatIllegalArgumentException()
            .isThrownBy { ExtractionRequest.NONE.withSourceRevision(revision) }
            .withMessageContaining("sourceLocator is required")
    }

    @Test
    fun `a revision must name the locator it travels with`() {
        val otherLocator = ContentAddressedLocator("some other source")

        assertThatIllegalArgumentException()
            .isThrownBy {
                ExtractionRequest(sourceLocator = otherLocator, sourceRevision = revision)
            }
            .withMessageContaining("must match")

        assertThatIllegalArgumentException()
            .isThrownBy {
                ExtractionRequest(sourceLocator = otherLocator).withSourceRevision(revision)
            }
            .withMessageContaining("must match")

        // Replacing the locator under a revision that already matched is caught the same way.
        assertThatIllegalArgumentException()
            .isThrownBy {
                ExtractionRequest(sourceLocator = locator, sourceRevision = revision)
                    .withSourceLocator(otherLocator)
            }
            .withMessageContaining("must match")
    }

    @Test
    fun `a profile is checked against nothing`() {
        // Profile and source provenance are independent, so every combination is constructible.
        assertThat(ExtractionRequest(profile = profile).profile).isSameAs(profile)
        assertThat(
            ExtractionRequest(sourceLocator = locator, profile = profile).sourceLocator,
        ).isSameAs(locator)
        assertThat(
            ExtractionRequest(
                sourceLocator = locator,
                sourceRevision = revision,
                profile = profile,
            ).profile,
        ).isSameAs(profile)
    }

    @Test
    fun `the copy helpers change one field and leave the rest alone`() {
        val base = ExtractionRequest(sourceLocator = locator, profile = profile)

        val revised = base.withSourceRevision(revision)
        assertThat(revised).isEqualTo(base.copy(sourceRevision = revision))
        assertThat(base.sourceRevision).isNull()

        val reprofiled = base.withProfile(ExtractionContentProfileRef("legal-review", "v2"))
        assertThat(reprofiled.sourceLocator).isSameAs(locator)
        assertThat(reprofiled.profile).isEqualTo(ExtractionContentProfileRef("legal-review", "v2"))
    }

    @Test
    fun `two requests carrying the same values are the same request`() {
        val one = ExtractionRequest(
            sourceLocator = UriLocator("https://example.com/source"),
            sourceRevision = SourceRevisionRef(locator.key(), "r1"),
            profile = ExtractionContentProfileRef("house-style", "v1"),
        )
        val two = ExtractionRequest(
            sourceLocator = UriLocator("https://example.com/source"),
            sourceRevision = SourceRevisionRef(locator.key(), "r1"),
            profile = ExtractionContentProfileRef("house-style", "v1"),
        )

        assertThat(one).isEqualTo(two)
        assertThat(one.hashCode()).isEqualTo(two.hashCode())
        assertThat(one).isNotEqualTo(one.withProfile(ExtractionContentProfileRef("house-style", "v2")))
    }
}
