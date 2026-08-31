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
 * it can refuse to read an exception message. It cannot tell a pseudonym from a username. So the
 * assertions are about what the types enforce — shapes that cannot be stored, and the one path
 * DICE itself uses being incapable of carrying source text — not about a claim that no host can
 * ever put something regrettable in a token.
 */
class ExtractionRunPrivacyTest {

    private val emailShape = Regex("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}")
    private val urlShape = Regex("(?i)\\b(?:https?|ftp|file|jdbc|bolt|neo4j)://")
    private val longDigitRun = Regex("\\d{9,}")

    @Test
    fun `a failure built from a provider exception carries none of the source text`() {
        // The leak this guards: extraction runs over known material, the provider throws, and its
        // message quotes the prompt — which is the source text — back at us.
        val thrown = runCatching { extractPropositionsFrom(SOURCE_TEXT) }.exceptionOrNull()!!
        assertThat(thrown.message).contains("Marguerite Okonkwo")
        assertThat(thrown.cause?.message).contains("AB-7741-XZ")

        val failure = ExtractionFailure.fromThrowable(ExtractionFailureCode.DECODE_FAILED, thrown)

        assertThat(failure.detail)
            .isEqualTo("java.lang.IllegalStateException <- java.lang.IllegalArgumentException")
        SOURCE_TEXT_FRAGMENTS.forEach { fragment ->
            assertThat(failure.detail).doesNotContain(fragment)
        }
    }

    @Test
    fun `no fragment of the source text survives into a stored run`() {
        val row = serializedRow(ExtractionRunFixtures.populatedRun())

        // The run's only failure was built from the exception above, so if any part of the message
        // path leaked, one of these fragments would be in the row.
        SOURCE_TEXT_FRAGMENTS.forEach { fragment ->
            assertThat(row).doesNotContain(fragment)
        }
        assertThat(row).doesNotContain(SOURCE_TEXT)
        // The row really did reach the failure record, so the assertion above is not vacuous.
        assertThat(row).contains("DECODE_FAILED", "java.lang.IllegalStateException")
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
        // Not in the summary: the tokens, the digests, the failure detail.
        assertThat(rendered).doesNotContain("actor:7f19aa02", "sha256:6d1f0a2b", "IllegalStateException")
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
    fun `a caller-written failure detail is flattened and clipped, and DICE says it cannot vouch for it`() {
        val wordy = "chunk 3 of 12\n exceeded the budget\t" + "x".repeat(1_000)

        val failure = ExtractionFailure.of(ExtractionFailureCode.SCHEMA_VIOLATION, wordy)

        assertThat(failure.detail).hasSize(ExtractionRunLimits.MAX_FAILURE_DETAIL_LENGTH)
        assertThat(failure.detail).startsWith("chunk 3 of 12 exceeded the budget x")
        assertThat(failure.detail).doesNotContain("\n")

        // The constructor is stricter than the factory: it rejects rather than clipping, so a
        // failure record can never be constructed over the bound by accident.
        assertThatIllegalArgumentException().isThrownBy {
            ExtractionFailure(ExtractionFailureCode.SCHEMA_VIOLATION, "y".repeat(513))
        }
        assertThatIllegalArgumentException().isThrownBy {
            ExtractionFailure(ExtractionFailureCode.SCHEMA_VIOLATION, "two\nlines")
        }.withMessageContaining("single line")
    }

    @Test
    fun `a cause chain is bounded and a self-referencing cause terminates`() {
        val deep = (1..12).fold(RuntimeException("root") as Throwable) { cause, _ ->
            IllegalStateException("wrapper", cause)
        }
        val failure = ExtractionFailure.fromThrowable(ExtractionFailureCode.INTERNAL, deep)

        assertThat(failure.detail.split(" <- ")).hasSize(ExtractionFailure.MAX_CAUSE_CHAIN)

        val selfCausing = SelfCausingException()
        assertThat(
            ExtractionFailure.fromThrowable(ExtractionFailureCode.INTERNAL, selfCausing).detail,
        ).isEqualTo(SelfCausingException::class.java.name)
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

    private class SelfCausingException : RuntimeException("self") {
        override val cause: Throwable get() = this
    }

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
