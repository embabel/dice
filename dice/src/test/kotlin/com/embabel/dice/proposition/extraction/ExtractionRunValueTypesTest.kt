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

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatIllegalArgumentException
import org.junit.jupiter.api.Test
import java.time.Duration

/**
 * The construction and validation matrix for the smaller value types the run header is built from.
 */
class ExtractionRunValueTypesTest {

    private val overLong = "x".repeat(ExtractionRunLimits.MAX_IDENTIFIER_LENGTH + 1)

    @Test
    fun `requested configuration accepts the portable fields`() {
        val config = ExtractionRequestedModelConfig.of(
            modelRole = "extraction",
            requestedModel = "model-large",
            temperature = 0.0,
            topP = 1.0,
            topK = 1,
            maxTokens = 1,
            presencePenalty = -2.0,
            frequencyPenalty = 2.0,
            thinkingFingerprint = "think:8fa2",
            selectionFingerprint = "select:11de",
            timeout = Duration.ofMillis(1),
        )

        assertThat(config.requestedModel).isEqualTo("model-large")
        assertThat(config.temperature).isZero()
        assertThat(config.timeout).isEqualTo(Duration.ofMillis(1))
        assertThat(ExtractionRequestedModelConfig()).isEqualTo(ExtractionRequestedModelConfig.of())
    }

    @Test
    fun `requested configuration rejects values no provider would mean`() {
        val rejections: List<Pair<String, () -> Any>> = listOf(
            "blank role" to { ExtractionRequestedModelConfig(modelRole = " ") },
            "over-long model" to { ExtractionRequestedModelConfig(requestedModel = overLong) },
            "negative temperature" to { ExtractionRequestedModelConfig(temperature = -0.1) },
            "not-a-number temperature" to { ExtractionRequestedModelConfig(temperature = Double.NaN) },
            "topP above one" to { ExtractionRequestedModelConfig(topP = 1.1) },
            "topP below zero" to { ExtractionRequestedModelConfig(topP = -0.1) },
            "infinite penalty" to {
                ExtractionRequestedModelConfig(presencePenalty = Double.POSITIVE_INFINITY)
            },
            "zero topK" to { ExtractionRequestedModelConfig(topK = 0) },
            "zero maxTokens" to { ExtractionRequestedModelConfig(maxTokens = 0) },
            "zero timeout" to { ExtractionRequestedModelConfig(timeout = Duration.ZERO) },
            "negative timeout" to { ExtractionRequestedModelConfig(timeout = Duration.ofSeconds(-1)) },
        )

        rejections.forEach { (name, construct) ->
            assertThatIllegalArgumentException().describedAs(name).isThrownBy { construct() }
        }
    }

    @Test
    fun `temperature has no upper bound because providers disagree on one`() {
        // Some services stop at 1 and some at 2. Rejecting a legitimate 2.0 would be DICE deciding
        // for a provider it never talks to.
        assertThat(ExtractionRequestedModelConfig(temperature = 2.0).temperature).isEqualTo(2.0)
    }

    @Test
    fun `usage records what a provider reported and nothing derived`() {
        val usage = ExtractionModelUsage.of(inputTokens = 100, outputTokens = 20, totalTokens = 7)

        // 100 + 20 is not 7, and that stands: an observed record records what was observed.
        assertThat(usage.totalTokens).isEqualTo(7)
        assertThat(ExtractionModelUsage().inputTokens).isNull()

        listOf<() -> Any>(
            { ExtractionModelUsage(inputTokens = -1) },
            { ExtractionModelUsage(outputTokens = -1) },
            { ExtractionModelUsage(totalTokens = -1) },
            { ExtractionModelUsage(cachedInputTokens = -1) },
            { ExtractionModelUsage(reasoningTokens = -1) },
        ).forEach { construct -> assertThatIllegalArgumentException().isThrownBy { construct() } }
    }

    @Test
    fun `provider response facts stay absent rather than being filled in from the request`() {
        val absent = ExtractionProviderResponseFacts()

        assertThat(absent.responseModel).isNull()
        assertThat(absent.finishReason).isNull()

        val reported = ExtractionProviderResponseFacts.of(
            responseModel = "model-large-2026-07",
            responseId = "resp:c40d19ab",
            finishReason = "stop",
            systemFingerprint = "fp:31ac70",
        )
        assertThat(reported.responseModel).isEqualTo("model-large-2026-07")

        assertThatIllegalArgumentException()
            .isThrownBy { ExtractionProviderResponseFacts(responseId = overLong) }
        assertThatIllegalArgumentException()
            .isThrownBy { ExtractionProviderResponseFacts(finishReason = "") }
    }

    @Test
    fun `fingerprints and runtime identity are bounded identifiers`() {
        assertThat(
            ExtractionRunFingerprints.of(schemaFingerprint = "sha256:a7c40e19").schemaFingerprint,
        ).isEqualTo("sha256:a7c40e19")
        assertThat(ExtractionRunFingerprints()).isEqualTo(ExtractionRunFingerprints.of())
        assertThatIllegalArgumentException()
            .isThrownBy { ExtractionRunFingerprints(promptTemplateFingerprint = overLong) }
        assertThatIllegalArgumentException()
            .isThrownBy { ExtractionRunFingerprints(metamodelFingerprint = " ") }

        assertThat(ExtractionRuntimeIdentity.of(extractor = "LlmPropositionExtractor").extractor)
            .isEqualTo("LlmPropositionExtractor")
        assertThatIllegalArgumentException()
            .isThrownBy { ExtractionRuntimeIdentity(hostApplication = overLong) }
        assertThatIllegalArgumentException()
            .isThrownBy { ExtractionRuntimeIdentity(runtimeVersion = "") }
    }

    @Test
    fun `counts start at zero and never go below it`() {
        assertThat(ExtractionRunCounts()).isEqualTo(ExtractionRunCounts.of())
        assertThat(ExtractionRunCounts().propositionsExtracted).isZero()

        listOf<() -> Any>(
            { ExtractionRunCounts(sourcesRead = -1) },
            { ExtractionRunCounts(chunksProcessed = -1) },
            { ExtractionRunCounts(propositionsExtracted = -1) },
            { ExtractionRunCounts(propositionsPersisted = -1) },
            { ExtractionRunCounts(propositionsRejected = -1) },
            { ExtractionRunCounts(entitiesResolved = -1) },
        ).forEach { construct -> assertThatIllegalArgumentException().isThrownBy { construct() } }
    }

    @Test
    fun `a run may have some subject references and not others`() {
        val partial = ExtractionRunSubjectRefs.of(
            actor = ExtractionActorRef("actor:7f19aa02"),
            deployment = ExtractionDeploymentRef("deploy:eu-west-1.blue"),
        )

        assertThat(partial.actor).isNotNull()
        assertThat(partial.session).isNull()
        assertThat(partial.personalization).isNull()
        assertThat(ExtractionRunSubjectRefs()).isEqualTo(ExtractionRunSubjectRefs.of())
    }

    @Test
    fun `a failure is a code, and everything else is optional`() {
        val bare = ExtractionFailure(ExtractionFailureCode.MODEL_TIMEOUT)

        assertThat(bare.code).isEqualTo(ExtractionFailureCode.MODEL_TIMEOUT)
        assertThat(bare.stage).isNull()
        assertThat(bare.providerStatus).isNull()
        assertThat(bare.measure).isNull()
        assertThat(bare.invocation).isNull()

        // "the provider's quota ran out on the second try at call 2" said in the vocabulary.
        val tied = ExtractionFailure.of(
            code = ExtractionFailureCode.RATE_LIMITED,
            stage = ExtractionFailureStage.MODEL_CALL,
            providerStatus = 429,
            measure = ExtractionFailureMeasure(ExtractionFailureQuantity.RETRY_AFTER_SECONDS, 60),
            at = ExtractionRunFixtures.FINISHED_AT,
            invocation = ExtractionInvocationId(2, 3),
        )
        assertThat(tied.invocation).isEqualTo(ExtractionInvocationId(2, 3))
        assertThat(tied.stage).isEqualTo(ExtractionFailureStage.MODEL_CALL)
        assertThat(tied.providerStatus).isEqualTo(429)
        assertThat(tied.measure)
            .isEqualTo(ExtractionFailureMeasure.of(ExtractionFailureQuantity.RETRY_AFTER_SECONDS, 60))
        assertThat(tied.measure.toString()).isEqualTo("RETRY_AFTER_SECONDS=60")
    }

    @Test
    fun `every extraction run type is marked experimental`() {
        // #67 will move these. The marker says so at the call site, not only in the CHANGELOG.
        listOf(
            ExtractionRun::class.java,
            ExtractionRunKey::class.java,
            ExtractionRunLineage::class.java,
            ExtractionRunStatus::class.java,
            ExtractionReplayFidelity::class.java,
            ExtractionRunLimits::class.java,
            ExtractionRunFingerprints::class.java,
            ExtractionRuntimeIdentity::class.java,
            ExtractionRunCounts::class.java,
            ExtractionRunSubjectRefs::class.java,
            ExtractionRequestedModelConfig::class.java,
            ExtractionInvocationId::class.java,
            ExtractionInvocationOutcome::class.java,
            ExtractionInvocationRecord::class.java,
            ExtractionModelUsage::class.java,
            ExtractionProviderResponseFacts::class.java,
            ExtractionFailure::class.java,
            ExtractionFailureCode::class.java,
            ExtractionFailureStage::class.java,
            ExtractionFailureQuantity::class.java,
            ExtractionFailureMeasure::class.java,
            ProtectedContentRef::class.java,
            ExtractionOpaqueRef::class.java,
            ExtractionActorRef::class.java,
            ExtractionRequestRef::class.java,
            ExtractionSessionRef::class.java,
            ExtractionPersonalizationRef::class.java,
            ExtractionDeploymentRef::class.java,
            ExtractionExperimentRef::class.java,
            ExtractionCohortRef::class.java,
            ExtractionRunStore::class.java,
            InMemoryExtractionRunStore::class.java,
            ExtractionRunTransition::class.java,
            ExtractionRunTransitionOutcome::class.java,
            ExtractionRunTransitionResult::class.java,
            ExtractionRunFingerprint::class.java,
            ExtractionRunNotFoundException::class.java,
            ExtractionRunConflictException::class.java,
            com.embabel.dice.common.ExtractionRunTransitioned::class.java,
            PropositionRunLinkStore::class.java,
            InMemoryPropositionRunLinkStore::class.java,
            PropositionRunLinkScopeException::class.java,
        ).forEach { type ->
            assertThat(isMarkedExperimental(type))
                .describedAs("%s is marked experimental", type.simpleName)
                .isTrue()
        }
    }

    /**
     * `@ApiStatus.Experimental` has class retention, so reflection cannot see it at runtime. The
     * marker is in the class file's constant pool either way, which is what this reads.
     */
    private fun isMarkedExperimental(type: Class<*>): Boolean {
        val bytes = checkNotNull(type.classLoader.getResourceAsStream(type.name.replace('.', '/') + ".class")) {
            "no class file for ${type.name}"
        }.use { it.readBytes() }
        return String(bytes, Charsets.ISO_8859_1)
            .contains("Lorg/jetbrains/annotations/ApiStatus\$Experimental;")
    }
}
