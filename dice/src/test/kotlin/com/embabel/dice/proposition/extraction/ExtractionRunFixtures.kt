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

import com.embabel.agent.core.ContextId
import com.embabel.dice.provenance.SourceRevisionRef
import java.time.Duration
import java.time.Instant

/**
 * Shared material for the extraction-run tests.
 *
 * The instants are fixed rather than `now()` so a dump of a populated run is byte-stable and so no
 * nanosecond field turns into a nine-digit run that the privacy assertions would flag.
 */
internal object ExtractionRunFixtures {

    /**
     * The source text the sanitization tests extract from.
     *
     * It carries a person's name, an organisation, an email address and a case number on purpose:
     * those are the shapes that survive into a provider's exception message and then into a stored
     * failure record if anything writes `e.message` into one.
     */
    const val SOURCE_TEXT: String =
        "Marguerite Okonkwo confirmed the Q3 renewal for Acme Holdings on 12 March 2026; " +
            "reach her at marguerite.okonkwo@acme-holdings.example about case AB-7741-XZ."

    /** Distinctive pieces of [SOURCE_TEXT], each of which must not appear in a stored run. */
    val SOURCE_TEXT_FRAGMENTS: List<String> = listOf(
        "Marguerite",
        "Okonkwo",
        "Acme Holdings",
        "Q3 renewal",
        "AB-7741-XZ",
        "marguerite.okonkwo@acme-holdings.example",
        "12 March 2026",
    )

    val CONTEXT: ContextId = ContextId("tenant-4f2c9a")
    val OTHER_CONTEXT: ContextId = ContextId("tenant-9b31de")

    val RUN: ExtractionRunRef = ExtractionRunRef("run-01JAV7Q2N4")
    val PARENT_RUN: ExtractionRunRef = ExtractionRunRef("run-01JAV6M0K1")
    val SUPERSEDED_RUN: ExtractionRunRef = ExtractionRunRef("run-01JAV5C8H7")

    val STARTED_AT: Instant = Instant.parse("2026-08-31T10:15:30Z")
    val FINISHED_AT: Instant = Instant.parse("2026-08-31T10:15:47Z")

    val REVISION_ONE: SourceRevisionRef = SourceRevisionRef("uri:doc-a", "rev-11")
    val REVISION_TWO: SourceRevisionRef = SourceRevisionRef("uri:doc-b", "rev-4")

    /** A minimal run: started, nothing else known. */
    fun startedRun(
        contextId: ContextId = CONTEXT,
        runRef: ExtractionRunRef = RUN,
    ): ExtractionRun = ExtractionRun(
        contextId = contextId,
        lineage = ExtractionRunLineage.root(runRef),
        status = ExtractionRunStatus.RUNNING,
        startedAt = STARTED_AT,
    )

    /**
     * A run with every field populated, which is what the privacy assertions dump.
     *
     * The failure on it is built the way DICE builds one — from a throwable whose message quotes
     * [SOURCE_TEXT], exactly as a provider's would.
     */
    fun populatedRun(): ExtractionRun = ExtractionRun(
        contextId = CONTEXT,
        lineage = ExtractionRunLineage.childOf(
            runRef = RUN,
            parent = ExtractionRunLineage.root(PARENT_RUN),
            supersedesRunRef = SUPERSEDED_RUN,
        ),
        status = ExtractionRunStatus.FAILED,
        startedAt = STARTED_AT,
        finishedAt = FINISHED_AT,
        profile = ExtractionContentProfileRef("house-style", "v3"),
        sourceRevisions = listOf(REVISION_ONE, REVISION_TWO),
        fingerprints = ExtractionRunFingerprints(
            promptTemplateFingerprint = "sha256:6d1f0a2b",
            schemaFingerprint = "sha256:a7c40e19",
            metamodelFingerprint = "sha256:0b93cc55",
        ),
        runtime = ExtractionRuntimeIdentity(
            extractor = "LlmPropositionExtractor",
            extractorVersion = "0.2.0",
            hostApplication = "assistant",
            runtime = "dice",
            runtimeVersion = "0.2.0",
        ),
        requestedModel = ExtractionRequestedModelConfig(
            modelRole = "extraction",
            requestedModel = "model-large",
            temperature = 0.2,
            topP = 0.9,
            topK = 40,
            maxTokens = 2048,
            presencePenalty = 0.0,
            frequencyPenalty = 0.1,
            thinkingFingerprint = "think:8fa2",
            selectionFingerprint = "select:11de",
            timeout = Duration.ofSeconds(30),
        ),
        subjectRefs = ExtractionRunSubjectRefs(
            actor = ExtractionActorRef("actor:7f19aa02"),
            request = ExtractionRequestRef("req:5c8e1d34"),
            session = ExtractionSessionRef("sess:2b70ffa9"),
            personalization = ExtractionPersonalizationRef("pers:e14c7b60"),
            deployment = ExtractionDeploymentRef("deploy:eu-west-1.blue"),
        ),
        experimentRef = ExtractionExperimentRef("exp:prompt-v3"),
        cohortRef = ExtractionCohortRef("cohort:treatment"),
        replayFidelity = ExtractionReplayFidelity.strongest(),
        counts = ExtractionRunCounts(
            sourcesRead = 2,
            chunksProcessed = 6,
            propositionsExtracted = 14,
            propositionsPersisted = 11,
            propositionsRejected = 3,
            entitiesResolved = 9,
        ),
        invocations = listOf(
            ExtractionInvocationRecord(
                id = ExtractionInvocationId.planned(0),
                outcome = ExtractionInvocationOutcome.SUCCEEDED,
                configuredService = "service-alpha",
                startedAt = STARTED_AT,
                finishedAt = STARTED_AT.plusSeconds(4),
                usage = ExtractionModelUsage(
                    inputTokens = 1820,
                    outputTokens = 344,
                    totalTokens = 2164,
                    cachedInputTokens = 512,
                    reasoningTokens = 96,
                ),
                providerResponse = ExtractionProviderResponseFacts(
                    responseModel = "model-large-2026-07",
                    responseId = "resp:c40d19ab",
                    finishReason = "stop",
                    systemFingerprint = "fp:31ac70",
                ),
            ),
            ExtractionInvocationRecord(
                id = ExtractionInvocationId(invocationIndex = 1, attempt = 2),
                outcome = ExtractionInvocationOutcome.FAILED,
                configuredService = "service-beta",
                startedAt = STARTED_AT.plusSeconds(5),
                finishedAt = FINISHED_AT,
            ),
        ),
        failures = listOf(
            ExtractionFailure.fromThrowable(
                code = ExtractionFailureCode.DECODE_FAILED,
                throwable = providerFailureQuotingSource(),
                at = FINISHED_AT,
                invocation = ExtractionInvocationId(invocationIndex = 1, attempt = 2),
            ),
        ),
    )

    /**
     * The exception a provider throws when a call over [SOURCE_TEXT] goes wrong: the prompt, and
     * therefore the source text, is quoted back in the message, and again in the cause.
     */
    fun providerFailureQuotingSource(): Throwable = IllegalStateException(
        "decode failed for prompt: $SOURCE_TEXT",
        IllegalArgumentException("unexpected token near '$SOURCE_TEXT'"),
    )
}
