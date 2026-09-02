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

import com.embabel.dice.proposition.extraction.ExtractionRunFixtures.SOURCE_TEXT
import com.embabel.dice.proposition.extraction.ExtractionRunFixtures.SOURCE_TEXT_FRAGMENTS
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatIllegalArgumentException
import org.junit.jupiter.api.Test
import java.lang.reflect.Field
import java.lang.reflect.Modifier

/**
 * What a stored run may and may not contain.
 *
 * The strong assertions here run over a *serialized row*: every field of a fully populated run,
 * reached by reflection rather than through `toString`, so a field the summary leaves out is still
 * covered. `toString` gets its own, weaker check, because a run reaches logs that way.
 *
 * The honest limit of these tests: a value type can bound a string and restrict its characters, and
 * a type with no string field at all can refuse every string. Neither can tell a pseudonym from a
 * username. So the assertions are about what the types enforce — shapes that cannot be stored, and
 * a failure record with nowhere to put source text — and they make no claim that a host cannot put
 * something regrettable in a token.
 *
 * The failure vocabulary gets its own file, [ExtractionFailureVocabularyTest], which pins the
 * closed shape itself.
 */
class ExtractionRunPrivacyTest {

    private val emailShape = Regex("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}")
    private val urlShape = Regex("(?i)\\b(?:https?|ftp|file|jdbc|bolt|neo4j)://")
    private val longDigitRun = Regex("\\d{9,}")

    @Test
    fun `a provider exception quoting the source has no way into a failure record`() {
        // The leak this guards: extraction runs over known material, the provider throws, and its
        // message quotes the prompt — which is the source text — back at us.
        val thrown = runCatching { extractPropositionsFrom(SOURCE_TEXT) }.exceptionOrNull()!!
        assertThat(thrown.message).contains("Marguerite Okonkwo")
        assertThat(thrown.cause?.message).contains("AB-7741-XZ")

        // No constructor takes that exception, and none takes a string pulled out of it.
        val constructorParameters =
            ExtractionFailure::class.java.declaredConstructors.flatMap { it.parameterTypes.asList() }
        assertThat(constructorParameters)
            .doesNotContain(Throwable::class.java, String::class.java, CharSequence::class.java)

        // What the caller can record is the classification, and a dump of it holds no fragment.
        val failure = ExtractionFailure(
            code = ExtractionFailureCode.DECODE_FAILED,
            stage = ExtractionFailureStage.RESPONSE_DECODE,
            providerStatus = 502,
        )
        val row = serializedRow(failure)
        SOURCE_TEXT_FRAGMENTS.forEach { fragment ->
            assertThat(row).doesNotContain(fragment)
        }
        assertThat(row).contains("DECODE_FAILED", "RESPONSE_DECODE", "502")
    }

    @Test
    fun `no fragment of the source text survives into a stored run`() {
        val row = serializedRow(ExtractionRunFixtures.populatedRun())

        // The run's only failure records the provider exception above, so if any part of the
        // message path leaked, one of these fragments would be in the row.
        SOURCE_TEXT_FRAGMENTS.forEach { fragment ->
            assertThat(row).doesNotContain(fragment)
        }
        assertThat(row).doesNotContain(SOURCE_TEXT)
        // The row really did reach the failure record, so the assertion above is not vacuous.
        assertThat(row).contains("DECODE_FAILED", "RESPONSE_DECODE", "CHARACTER_COUNT")
    }

    @Test
    fun `a populated run holds no address, no link, and no long identifier run`() {
        val row = serializedRow(ExtractionRunFixtures.populatedRun())

        assertThat(emailShape.find(row)?.value).isNull()
        assertThat(urlShape.find(row)?.value).isNull()
        assertThat(longDigitRun.find(row)?.value).isNull()
        // The row is a real dump: it reaches the tokens, the fingerprints and the counts.
        assertThat(row).contains("actor:7f19aa02", "sha256:6d1f0a2b", "propositionsPersisted=11")
    }

    @Test
    fun `toString shows identity and sizes and none of the payload`() {
        val rendered = ExtractionRunFixtures.populatedRun().toString()

        assertThat(rendered).contains("runId=run-01JAV7Q2N4", "rootRunId=run-01JAV6M0K1", "status=FAILED")
        assertThat(rendered).contains("sourceRevisions=2", "invocations=2", "failures=1")
        // Left out of the summary: the tokens, the digests, and everything on a failure past its
        // count.
        assertThat(rendered).doesNotContain("actor:7f19aa02", "sha256:6d1f0a2b", "RESPONSE_DECODE")
        SOURCE_TEXT_FRAGMENTS.forEach { fragment -> assertThat(rendered).doesNotContain(fragment) }
    }

    @Test
    fun `an opaque token cannot be an address, a link, a path, or a name`() {
        val rejected = listOf(
            "marguerite.okonkwo@acme-holdings.example",
            "https://acme-holdings.example/users/4471",
            "/var/run/secrets/token",
            "C:\\Users\\marguerite",
            "Marguerite Okonkwo",
            """{"userId":"4471"}""",
            "token with spaces",
            "line\nbreak",
        )

        rejected.forEach { candidate ->
            assertThatIllegalArgumentException()
                .describedAs("token %s", candidate)
                .isThrownBy { ExtractionActorRef(candidate) }
        }

        // What a host should mint instead: uuids, ULIDs, hex digests, namespaced opaque ids.
        listOf(
            "9f2a4c1e-3b77-4d0a-9c11-0a4e2b6d8f31",
            "01JAV7Q2N4KX8ZP3W6Y5M0R7TB",
            "actor:7f19aa02",
            "sha256_6d1f0a2b3c4d",
            "~tilde.and-dash_ok",
        ).forEach { candidate -> assertThat(ExtractionActorRef(candidate).token).isEqualTo(candidate) }
    }

    @Test
    fun `a rejected token never appears in the message that rejects it`() {
        // A validation failure propagates into logs, and the value that failed validation is
        // exactly the one nobody vouched for.
        val secretish = "marguerite.okonkwo@acme-holdings.example"
        val overLong = "a".repeat(ExtractionRunLimits.MAX_IDENTIFIER_LENGTH + 1)

        val badShape = runCatching { ExtractionSessionRef(secretish) }.exceptionOrNull()!!
        val tooLong = runCatching { ExtractionSessionRef(overLong) }.exceptionOrNull()!!

        assertThat(badShape.message).doesNotContain(secretish)
        assertThat(badShape.message).contains("ExtractionSessionRef")
        assertThat(tooLong.message).doesNotContain(overLong)
        assertThat(tooLong.message).contains("257")
    }

    @Test
    fun `a token is bounded and blank is not a token`() {
        val atCap = "a".repeat(ExtractionRunLimits.MAX_IDENTIFIER_LENGTH)

        assertThat(ExtractionDeploymentRef(atCap).token).hasSize(ExtractionRunLimits.MAX_IDENTIFIER_LENGTH)
        assertThatIllegalArgumentException().isThrownBy { ExtractionDeploymentRef(atCap + "a") }
        assertThatIllegalArgumentException().isThrownBy { ExtractionDeploymentRef("  ") }
    }

    @Test
    fun `toString on a token shows a prefix`() {
        val ref = ExtractionPersonalizationRef("pers:e14c7b60cafe")

        assertThat(ref.toString()).isEqualTo("ExtractionPersonalizationRef(token=pers:e14…)")
        assertThat(ref.toString()).doesNotContain("cafe")
    }

    @Test
    fun `two kinds of reference holding the same token are different references`() {
        val actor = ExtractionActorRef("shared-token")
        val session = ExtractionSessionRef("shared-token")

        assertThat(actor).isNotEqualTo(session)
        assertThat(session).isNotEqualTo(actor)
        assertThat(actor).isEqualTo(ExtractionActorRef("shared-token"))
        assertThat(actor.hashCode()).isEqualTo(ExtractionActorRef("shared-token").hashCode())
        assertThat(setOf(actor, session)).hasSize(2)
    }

    @Test
    fun `the numbers on a failure are checked, and a rejection quotes only the number`() {
        val failure = ExtractionFailure(
            code = ExtractionFailureCode.RATE_LIMITED,
            providerStatus = 429,
            measure = ExtractionFailureMeasure(ExtractionFailureQuantity.RETRY_AFTER_SECONDS, 30),
        )

        assertThat(failure.providerStatus).isEqualTo(429)
        assertThat(failure.measure?.value).isEqualTo(30)

        assertThatIllegalArgumentException()
            .isThrownBy { ExtractionFailure(ExtractionFailureCode.RATE_LIMITED, providerStatus = 99) }
            .withMessageContaining("HTTP status")
        assertThatIllegalArgumentException()
            .isThrownBy { ExtractionFailure(ExtractionFailureCode.RATE_LIMITED, providerStatus = 600) }
            .withMessageContaining("HTTP status")
        assertThatIllegalArgumentException()
            .isThrownBy { ExtractionFailureMeasure(ExtractionFailureQuantity.TOKEN_COUNT, -1) }
            .withMessageContaining("TOKEN_COUNT")
    }

    @Test
    fun `no replay fidelity value promises exact replay`() {
        val claimsTooMuch = listOf(
            "EXACT", "DETERMINISTIC", "REPRODUCIBLE", "IDENTICAL", "GUARANTEED", "FULL", "COMPLETE", "PERFECT",
        )

        ExtractionReplayFidelity.entries.forEach { fidelity ->
            claimsTooMuch.forEach { word ->
                assertThat(fidelity.name).doesNotContain(word)
            }
        }
        assertThat(ExtractionReplayFidelity.entries.map { it.name })
            .containsExactly("NONE", "METADATA", "APPROXIMATE")
        // The strongest value the model offers is named for what it is.
        assertThat(ExtractionReplayFidelity.strongest()).isEqualTo(ExtractionReplayFidelity.APPROXIMATE)
    }

    /**
     * Stands in for the extraction that would have produced this run: it reads known source text
     * and fails the way a provider fails, with the prompt quoted back.
     *
     * There is no run coordinator until a later slice, so nothing here can drive a real extraction
     * to a stored run. What this reproduces is the specific path a leak takes.
     */
    private fun extractPropositionsFrom(sourceText: String): Nothing =
        throw IllegalStateException(
            "decode failed for prompt: $sourceText",
            IllegalArgumentException("unexpected token near '$sourceText'"),
        )

    /**
     * Renders every field of a value, recursively, the way a row writer would see it.
     *
     * Deliberately not `toString`: the point is to see fields a summary omits. It recurses into
     * DICE types by reflection and renders anything else with `toString`, since JDK internals are
     * neither ours nor reachable.
     */
    private fun serializedRow(value: Any?, depth: Int = 0): String {
        if (value == null) return "null"
        if (depth > 16) return "…"
        return when {
            value is Enum<*> -> value.name
            value is CharSequence || value is Number || value is Boolean || value is Char -> value.toString()
            value is Iterable<*> -> value.joinToString(",", "[", "]") { serializedRow(it, depth + 1) }
            value is Map<*, *> -> value.entries.joinToString(",", "{", "}") {
                "${serializedRow(it.key, depth + 1)}=${serializedRow(it.value, depth + 1)}"
            }
            !value.javaClass.name.startsWith("com.embabel.") -> value.toString()
            else -> instanceFields(value.javaClass).joinToString(
                separator = ",",
                prefix = "${value.javaClass.simpleName}{",
                postfix = "}",
            ) { field ->
                field.isAccessible = true
                "${field.name}=${serializedRow(field.get(value), depth + 1)}"
            }
        }
    }

    private fun instanceFields(type: Class<*>): List<Field> {
        val fields = mutableListOf<Field>()
        var current: Class<*>? = type
        while (current != null && current != Any::class.java) {
            fields += current.declaredFields.filterNot { it.isSynthetic || Modifier.isStatic(it.modifiers) }
            current = current.superclass
        }
        return fields
    }
}
