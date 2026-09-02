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

import org.jetbrains.annotations.ApiStatus

/**
 * The digests that say what a run ran against.
 *
 * All three are opaque to DICE: it compares them and stores them and reads nothing out of them.
 * They are what makes "did the output change because the prompt changed?" answerable without
 * storing the prompt. A host that changes a template and forgets to change its fingerprint gets
 * runs it cannot tell apart, which is the host's contract to keep.
 *
 * Storing a digest and leaving the material behind is deliberate: a prompt template holds
 * instructions and often examples, and examples are where real content ends up.
 *
 * **Who writes [metamodelFingerprint].** The extraction coordinator, which arrives in a later
 * slice. It reads the declared schema stamp from the host's `DeclaredSchemaSource`, hashes the
 * content, and writes the hash here, once per run. Nothing in this slice produces one, so a run
 * built today carries whatever its caller passed.
 *
 * The fingerprint says what the whole run ran under. Asking which schema a single proposition was
 * extracted under is answered by following that proposition back to its run through run lineage,
 * which is why no per-proposition schema stamp exists.
 *
 * EXPERIMENTAL. The shape may still change while extraction runs (DICE #67) land.
 *
 * @property promptTemplateFingerprint Digest of the prompt or template the run used
 * @property schemaFingerprint Digest of the output schema the run asked the model to satisfy
 * @property metamodelFingerprint Content hash of the metamodel version in force, written by the
 *   extraction coordinator from the host's declared schema source
 */
@ApiStatus.Experimental
data class ExtractionRunFingerprints @JvmOverloads constructor(
    val promptTemplateFingerprint: String? = null,
    val schemaFingerprint: String? = null,
    val metamodelFingerprint: String? = null,
) {

    init {
        requireBoundedIdentifier(promptTemplateFingerprint, "promptTemplateFingerprint")
        requireBoundedIdentifier(schemaFingerprint, "schemaFingerprint")
        requireBoundedIdentifier(metamodelFingerprint, "metamodelFingerprint")
    }

    companion object {

        /** Java-friendly factory. */
        @JvmStatic
        @JvmOverloads
        fun of(
            promptTemplateFingerprint: String? = null,
            schemaFingerprint: String? = null,
            metamodelFingerprint: String? = null,
        ): ExtractionRunFingerprints = ExtractionRunFingerprints(
            promptTemplateFingerprint = promptTemplateFingerprint,
            schemaFingerprint = schemaFingerprint,
            metamodelFingerprint = metamodelFingerprint,
        )
    }
}

/**
 * What code ran the run, and where.
 *
 * The version fields are what separate "the extractor changed" from "the model changed" when
 * output quality moves. OpenLineage's processing-engine facet carries the same pair of a name and
 * a version for the same reason.
 *
 * [hostApplication] names the application embedding DICE, not a machine. A hostname would be a
 * direct identifier of infrastructure and belongs in the host's own telemetry.
 *
 * EXPERIMENTAL. The shape may still change while extraction runs (DICE #67) land.
 *
 * @property extractor Which extractor implementation ran
 * @property extractorVersion Its version
 * @property hostApplication The application that embeds DICE
 * @property runtime The runtime the run executed on, such as a DICE release or a service name
 * @property runtimeVersion Its version
 */
@ApiStatus.Experimental
data class ExtractionRuntimeIdentity @JvmOverloads constructor(
    val extractor: String? = null,
    val extractorVersion: String? = null,
    val hostApplication: String? = null,
    val runtime: String? = null,
    val runtimeVersion: String? = null,
) {

    init {
        requireBoundedIdentifier(extractor, "extractor")
        requireBoundedIdentifier(extractorVersion, "extractorVersion")
        requireBoundedIdentifier(hostApplication, "hostApplication")
        requireBoundedIdentifier(runtime, "runtime")
        requireBoundedIdentifier(runtimeVersion, "runtimeVersion")
    }

    companion object {

        /** Java-friendly factory. */
        @JvmStatic
        @JvmOverloads
        fun of(
            extractor: String? = null,
            extractorVersion: String? = null,
            hostApplication: String? = null,
            runtime: String? = null,
            runtimeVersion: String? = null,
        ): ExtractionRuntimeIdentity = ExtractionRuntimeIdentity(
            extractor = extractor,
            extractorVersion = extractorVersion,
            hostApplication = hostApplication,
            runtime = runtime,
            runtimeVersion = runtimeVersion,
        )
    }
}

/**
 * How much a run got through.
 *
 * Counts are what a run page sorts and filters on, so they sit on the header rather than being
 * derived by counting rows. They are recorded by whoever ran the extraction; nothing here
 * cross-checks one against another, because a run that extracted 40 propositions and persisted 12
 * is a real and interesting state, not a contradiction.
 *
 * EXPERIMENTAL. The shape may still change while extraction runs (DICE #67) land.
 *
 * @property sourcesRead How many source revisions the run read
 * @property chunksProcessed How many chunks reached extraction
 * @property propositionsExtracted How many propositions the model produced
 * @property propositionsPersisted How many were stored
 * @property propositionsRejected How many were dropped by a gate or a filter
 * @property entitiesResolved How many entity mentions were resolved
 */
@ApiStatus.Experimental
data class ExtractionRunCounts @JvmOverloads constructor(
    val sourcesRead: Int = 0,
    val chunksProcessed: Int = 0,
    val propositionsExtracted: Int = 0,
    val propositionsPersisted: Int = 0,
    val propositionsRejected: Int = 0,
    val entitiesResolved: Int = 0,
) {

    init {
        requireNonNegative(sourcesRead, "sourcesRead")
        requireNonNegative(chunksProcessed, "chunksProcessed")
        requireNonNegative(propositionsExtracted, "propositionsExtracted")
        requireNonNegative(propositionsPersisted, "propositionsPersisted")
        requireNonNegative(propositionsRejected, "propositionsRejected")
        requireNonNegative(entitiesResolved, "entitiesResolved")
    }

    companion object {

        /** Java-friendly factory. */
        @JvmStatic
        @JvmOverloads
        fun of(
            sourcesRead: Int = 0,
            chunksProcessed: Int = 0,
            propositionsExtracted: Int = 0,
            propositionsPersisted: Int = 0,
            propositionsRejected: Int = 0,
            entitiesResolved: Int = 0,
        ): ExtractionRunCounts = ExtractionRunCounts(
            sourcesRead = sourcesRead,
            chunksProcessed = chunksProcessed,
            propositionsExtracted = propositionsExtracted,
            propositionsPersisted = propositionsPersisted,
            propositionsRejected = propositionsRejected,
            entitiesResolved = entitiesResolved,
        )
    }
}

/**
 * The five pseudonymous references a run carries about whose work it was.
 *
 * Grouped together because they share one contract, stated on [ExtractionOpaqueRef]: bounded,
 * host-minted, no direct identifiers, nothing dereferenceable. Grouping them also keeps them
 * findable — a reader looking for "what does a run know about the user?" gets one type with five
 * fields and an answer.
 *
 * Every field is optional. A run started by a scheduled job has an actor reference and no session;
 * a run replaying archived material may have neither.
 *
 * EXPERIMENTAL. The shape may still change while extraction runs (DICE #67) land.
 *
 * @property actor Who the run acted for
 * @property request The inbound request that caused it
 * @property session The conversation or session the material came from
 * @property personalization The personalization state in force
 * @property deployment The deployment it executed in
 */
@ApiStatus.Experimental
data class ExtractionRunSubjectRefs @JvmOverloads constructor(
    val actor: ExtractionActorRef? = null,
    val request: ExtractionRequestRef? = null,
    val session: ExtractionSessionRef? = null,
    val personalization: ExtractionPersonalizationRef? = null,
    val deployment: ExtractionDeploymentRef? = null,
) {

    companion object {

        /** Java-friendly factory. */
        @JvmStatic
        @JvmOverloads
        fun of(
            actor: ExtractionActorRef? = null,
            request: ExtractionRequestRef? = null,
            session: ExtractionSessionRef? = null,
            personalization: ExtractionPersonalizationRef? = null,
            deployment: ExtractionDeploymentRef? = null,
        ): ExtractionRunSubjectRefs = ExtractionRunSubjectRefs(
            actor = actor,
            request = request,
            session = session,
            personalization = personalization,
            deployment = deployment,
        )
    }
}
