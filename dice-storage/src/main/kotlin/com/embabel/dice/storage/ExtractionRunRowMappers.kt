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
package com.embabel.dice.storage

import com.embabel.agent.core.ContextId
import com.embabel.dice.proposition.extraction.ExtractionActorRef
import com.embabel.dice.proposition.extraction.ExtractionCohortRef
import com.embabel.dice.proposition.extraction.ExtractionContentProfileRef
import com.embabel.dice.proposition.extraction.ExtractionDeploymentRef
import com.embabel.dice.proposition.extraction.ExtractionExperimentRef
import com.embabel.dice.proposition.extraction.ExtractionFailure
import com.embabel.dice.proposition.extraction.ExtractionFailureCode
import com.embabel.dice.proposition.extraction.ExtractionFailureMeasure
import com.embabel.dice.proposition.extraction.ExtractionFailureQuantity
import com.embabel.dice.proposition.extraction.ExtractionFailureStage
import com.embabel.dice.proposition.extraction.ExtractionInvocationId
import com.embabel.dice.proposition.extraction.ExtractionInvocationOutcome
import com.embabel.dice.proposition.extraction.ExtractionInvocationRecord
import com.embabel.dice.proposition.extraction.ExtractionModelUsage
import com.embabel.dice.proposition.extraction.ExtractionPersonalizationRef
import com.embabel.dice.proposition.extraction.ExtractionProviderResponseFacts
import com.embabel.dice.proposition.extraction.ExtractionReplayFidelity
import com.embabel.dice.proposition.extraction.ExtractionRequestRef
import com.embabel.dice.proposition.extraction.ExtractionRequestedModelConfig
import com.embabel.dice.proposition.extraction.ExtractionRun
import com.embabel.dice.proposition.extraction.ExtractionRunCounts
import com.embabel.dice.proposition.extraction.ExtractionRunFingerprint
import com.embabel.dice.proposition.extraction.ExtractionRunFingerprints
import com.embabel.dice.proposition.extraction.ExtractionRunLineage
import com.embabel.dice.proposition.extraction.ExtractionRunRef
import com.embabel.dice.proposition.extraction.ExtractionRunStatus
import com.embabel.dice.proposition.extraction.ExtractionRunSubjectRefs
import com.embabel.dice.proposition.extraction.ExtractionRunTransition
import com.embabel.dice.proposition.extraction.ExtractionRuntimeIdentity
import com.embabel.dice.proposition.extraction.ExtractionSessionRef
import com.embabel.dice.provenance.SourceRevisionRef
import com.fasterxml.jackson.databind.ObjectMapper
import java.time.Duration
import java.time.Instant

private val objectMapper = ObjectMapper()

/**
 * Translates extraction runs to and from the property maps the Neo4j graph store reads and writes.
 *
 * Neo4j properties are scalars and flat arrays, and a run header carries lists of value objects, so
 * the structured parts — source revisions, the requested model configuration, the failure list —
 * are serialized to JSON strings. JSON also survives the pipes, tabs, newlines and quotes that turn
 * up in strings coming out of LLM extraction, which a delimiter-joined encoding does not.
 *
 * **Invocation records are not here.** They are child rows with their own key and their own mapper,
 * [ExtractionInvocationRowMapper], and a header write never touches one. That is what makes a save
 * unable to delete a recorded attempt, and it is why the store gets the contract's
 * invocation-preservation rule for free rather than having to implement it.
 *
 * **Instants are written three ways.** The ISO-8601 string is what round-trips and what a person
 * reading a node wants; `Instant.parse` inverts `Instant.toString` exactly. The epoch second and the
 * nanosecond let the database sort and range-filter at full precision. Epoch milliseconds would
 * truncate — two runs started 500 microseconds apart would compare equal, leaving "newest first"
 * arbitrary between them, and a `since` bound falling inside a millisecond would sweep in runs
 * started just before it. Sorting on the ISO string has its own failure: `Instant.toString` writes
 * no fraction on a whole second and `'Z'` outranks `'.'`, so `12:00:00Z` sorts after `12:00:00.500Z`.
 *
 * **Reads are strict.** A property this mapper wrote must be there when it is read again. A node
 * missing one is corrupt, so the accessor throws and the store's surrounding guard logs the row and
 * skips it. Optional fields are the exception, and their absence means the run declared none: a
 * `SET` of `null` in Cypher leaves no property behind, so "no profile" and "no experiment label" are
 * stored as the absence of a property rather than as a sentinel.
 *
 * **The terminal fingerprint is never derived here.** It is the string
 * [ExtractionRunTransition.fingerprint] computed, stored verbatim on its own node and compared
 * verbatim. Re-deriving it from a stored run would make a correct retry that happened after another
 * attempt was recorded look like an incompatible rewrite.
 */
object ExtractionRunRowMapper {

    /**
     * The one derived property on a run header: an injective encoding of everything about a run's
     * lineage that a later save could disagree about.
     *
     * A save is rejected when it contradicts the stored lineage, and that check has to happen inside
     * the same statement that writes — otherwise a concurrent terminal write lands between the read
     * and the write. Comparing five properties in Cypher works but reads badly and gets the null
     * handling wrong easily; comparing one string is exact. The individual properties are still
     * stored separately, because that is what `childrenOf` and `runsOfRoot` seek on.
     *
     * Two sources of truth for one fact is a drift hazard, so [fromRow] re-derives this from the
     * properties it just read and refuses a node where the two disagree — the same shape of check
     * `DriftReportRowMapper` runs over a report's scope.
     */
    fun lineageKeyOf(lineage: ExtractionRunLineage): String = objectMapper.writeValueAsString(
        linkedMapOf(
            "runId" to lineage.runRef.runId,
            "rootRunId" to lineage.rootRunRef.runId,
            "parentRunId" to lineage.parentRunRef?.runId,
            "supersedesRunId" to lineage.supersedesRunRef?.runId,
            "passIndex" to lineage.passIndex,
        ),
    )

    /**
     * Everything a header write sets, minus the two key properties, which the MERGE pattern owns and
     * nothing may move.
     *
     * Null values are deliberate. `SET n += $header` removes a property bound to null, so an
     * optional field the run does not carry leaves no property behind, and a run saved again without
     * it is stored the way a run that never had it is stored.
     */
    fun headerBindMap(run: ExtractionRun): Map<String, Any?> = buildMap {
        put("status", run.status.name)
        put("lineageKey", lineageKeyOf(run.lineage))
        put("rootRunId", run.rootRef.runId)
        put("parentRunId", run.parentRef?.runId)
        put("supersedesRunId", run.lineage.supersedesRunRef?.runId)
        put("passIndex", run.lineage.passIndex)

        putInstant("startedAt", run.startedAt)
        putInstant("finishedAt", run.finishedAt)

        put("profileName", run.profile?.name)
        put("profileVersion", run.profile?.version)
        put("sourceRevisions", serializeSourceRevisions(run.sourceRevisions))

        put("promptTemplateFingerprint", run.fingerprints.promptTemplateFingerprint)
        put("schemaFingerprint", run.fingerprints.schemaFingerprint)
        put("metamodelFingerprint", run.fingerprints.metamodelFingerprint)

        put("extractor", run.runtime.extractor)
        put("extractorVersion", run.runtime.extractorVersion)
        put("hostApplication", run.runtime.hostApplication)
        // `runtime` on the node would read as "the runtime object"; the property holds the name.
        put("runtimeName", run.runtime.runtime)
        put("runtimeVersion", run.runtime.runtimeVersion)

        put("requestedModel", serializeRequestedModel(run.requestedModel))

        put("actorRef", run.subjectRefs.actor?.token)
        put("requestRef", run.subjectRefs.request?.token)
        put("sessionRef", run.subjectRefs.session?.token)
        put("personalizationRef", run.subjectRefs.personalization?.token)
        put("deploymentRef", run.subjectRefs.deployment?.token)
        put("experimentRef", run.experimentRef?.token)
        put("cohortRef", run.cohortRef?.token)

        put("replayFidelity", run.replayFidelity.name)
        putAll(countsBindMap(run.counts))
        put("failures", serializeFailures(run.failures))
    }

    /**
     * A digest of everything a header write owns, computed from the same map [headerBindMap]
     * builds and compared verbatim against what an earlier write stored — never re-derived from a
     * row read back afterward.
     *
     * This is what lets a save tell "nothing changed" from "something changed" atomically, in the
     * same lock-then-read a compare-and-set needs anyway: two fingerprints computed the same way
     * from the same set of fields are equal exactly when every field they cover is, so one string
     * compare stands in for comparing a dozen properties by hand, the way [lineageKeyOf] already
     * stands in for comparing lineage's five. `version` and this fingerprint itself are never part
     * of the map it digests — a resend naming a stale version has to produce the same digest as the
     * write that landed, or a byte-identical retry could never be told apart from a genuine change.
     *
     * Digested through [ExtractionRunFingerprint.ofFields], the canonical codec. A serializer's
     * output can move without the header moving with it — a different `Map` implementation
     * iterating [header] in a different order, a library upgrade changing how it renders a number —
     * and every one of those would read as a genuine change to a save that is really a no-op or a
     * byte-identical retry. The canonical encoding sorts by field name and renders every value as
     * the plain string it already is, so the digest is a function of the header's content alone.
     */
    fun headerFingerprint(header: Map<String, Any?>): String = ExtractionRunFingerprint.ofFields(
        ExtractionRunFingerprint.HEADER_VERSION,
        header.mapValues { (_, value) -> value?.toString() },
    )

    /**
     * The properties a terminal write sets, and only those.
     *
     * Counts and failures are on the map only when the transition carries them. Null means "keep
     * what the run recorded", so leaving the key out is exactly what
     * [ExtractionRunTransition.applyTo] does with `counts ?: run.counts` — and binding null instead
     * would remove the stored properties, which is the opposite.
     */
    fun terminalBindMap(transition: ExtractionRunTransition): Map<String, Any?> = buildMap {
        put("status", transition.status.name)
        putInstant("finishedAt", transition.finishedAt)
        transition.counts?.let { putAll(countsBindMap(it)) }
        transition.failures?.let { put("failures", serializeFailures(it)) }
    }

    /**
     * Rebuilds a run from a header node's properties and the child rows read alongside it.
     *
     * Throws on anything it cannot read, which is what lets the store log the node and skip it
     * rather than handing back a run with invented defaults. A run named `""` because `runId` was
     * missing would be indistinguishable from data.
     */
    fun fromRow(row: Map<*, *>, invocations: List<ExtractionInvocationRecord>): ExtractionRun {
        val lineage = ExtractionRunLineage.fromStoredFields(
            runRef = ExtractionRunRef(row.str("runId")),
            rootRunRef = ExtractionRunRef(row.str("rootRunId")),
            parentRunRef = row.strOrNull("parentRunId")?.let(::ExtractionRunRef),
            supersedesRunRef = row.strOrNull("supersedesRunId")?.let(::ExtractionRunRef),
            passIndex = row.int("passIndex"),
        )
        val storedLineageKey = row.str("lineageKey")
        require(storedLineageKey == lineageKeyOf(lineage)) {
            "ExtractionRun '${lineage.runRef.runId}' fails its lineage check: the stored lineageKey " +
                "does not match the lineage properties on the node, so a save comparing against it " +
                "would reach a different verdict than a read of the same node"
        }

        val profileName = row.strOrNull("profileName")
        val profileVersion = row.strOrNull("profileVersion")
        require((profileName == null) == (profileVersion == null)) {
            "ExtractionRun '${lineage.runRef.runId}' stores half a profile reference: a name and a " +
                "version are written together or not at all"
        }

        return ExtractionRun(
            contextId = ContextId(row.str("contextId")),
            lineage = lineage,
            status = row.enum<ExtractionRunStatus>("status"),
            startedAt = Instant.parse(row.str("startedAt")),
            finishedAt = row.strOrNull("finishedAt")?.let(Instant::parse),
            profile = profileName?.let { ExtractionContentProfileRef(it, profileVersion!!) },
            sourceRevisions = deserializeSourceRevisions(row.str("sourceRevisions")),
            fingerprints = ExtractionRunFingerprints(
                promptTemplateFingerprint = row.strOrNull("promptTemplateFingerprint"),
                schemaFingerprint = row.strOrNull("schemaFingerprint"),
                metamodelFingerprint = row.strOrNull("metamodelFingerprint"),
            ),
            runtime = ExtractionRuntimeIdentity(
                extractor = row.strOrNull("extractor"),
                extractorVersion = row.strOrNull("extractorVersion"),
                hostApplication = row.strOrNull("hostApplication"),
                runtime = row.strOrNull("runtimeName"),
                runtimeVersion = row.strOrNull("runtimeVersion"),
            ),
            requestedModel = deserializeRequestedModel(row.strOrNull("requestedModel")),
            subjectRefs = ExtractionRunSubjectRefs(
                actor = row.strOrNull("actorRef")?.let(::ExtractionActorRef),
                request = row.strOrNull("requestRef")?.let(::ExtractionRequestRef),
                session = row.strOrNull("sessionRef")?.let(::ExtractionSessionRef),
                personalization = row.strOrNull("personalizationRef")?.let(::ExtractionPersonalizationRef),
                deployment = row.strOrNull("deploymentRef")?.let(::ExtractionDeploymentRef),
            ),
            experimentRef = row.strOrNull("experimentRef")?.let(::ExtractionExperimentRef),
            cohortRef = row.strOrNull("cohortRef")?.let(::ExtractionCohortRef),
            replayFidelity = row.enum<ExtractionReplayFidelity>("replayFidelity"),
            counts = countsFromRow(row),
            invocations = invocations,
            failures = deserializeFailures(row.str("failures")),
            version = row.long("version"),
        )
    }

    /**
     * Counts are six scalar properties rather than one JSON blob, because they are what a later
     * run page will want to filter and aggregate on, and a JSON string is opaque to a query.
     */
    private fun countsBindMap(counts: ExtractionRunCounts): Map<String, Any?> = mapOf(
        "countSourcesRead" to counts.sourcesRead,
        "countChunksProcessed" to counts.chunksProcessed,
        "countPropositionsExtracted" to counts.propositionsExtracted,
        "countPropositionsPersisted" to counts.propositionsPersisted,
        "countPropositionsRejected" to counts.propositionsRejected,
        "countEntitiesResolved" to counts.entitiesResolved,
    )

    private fun countsFromRow(row: Map<*, *>): ExtractionRunCounts = ExtractionRunCounts(
        sourcesRead = row.int("countSourcesRead"),
        chunksProcessed = row.int("countChunksProcessed"),
        propositionsExtracted = row.int("countPropositionsExtracted"),
        propositionsPersisted = row.int("countPropositionsPersisted"),
        propositionsRejected = row.int("countPropositionsRejected"),
        entitiesResolved = row.int("countEntitiesResolved"),
    )
}

/**
 * Translates one attempt at one planned model call to and from its own node's properties.
 *
 * The key is `(contextId, runId, invocationIndex, attempt)`, allocated when the run's call plan is
 * laid out rather than when a call returns, so a retry writes its own row and a replayed write of
 * the same attempt upserts in place. Nothing here writes the key: it is in the MERGE pattern, where
 * the uniqueness constraint can see it.
 *
 * Usage and provider-response facts are optional records with several optional fields each, so both
 * are JSON strings. Two scalar properties per field would lose the difference between "the provider
 * reported nothing" and "there is no report", and that difference is the whole point of separating
 * what was asked for from what was observed.
 */
object ExtractionInvocationRowMapper {

    /** Bind values for one child row, key properties included so the MERGE pattern can use them. */
    fun bindMap(record: ExtractionInvocationRecord): Map<String, Any?> = buildMap {
        put("invocationIndex", record.invocationIndex)
        put("attempt", record.attempt)
        put("outcome", record.outcome.name)
        put("configuredService", record.configuredService)
        putInstant("startedAt", record.startedAt)
        putInstant("finishedAt", record.finishedAt)
        put("usage", serializeUsage(record.usage))
        put("providerResponse", serializeProviderResponse(record.providerResponse))
    }

    /** Rebuilds one attempt, throwing on anything the writer must have stored and did not. */
    fun fromRow(row: Map<*, *>): ExtractionInvocationRecord = ExtractionInvocationRecord(
        id = ExtractionInvocationId(
            invocationIndex = row.int("invocationIndex"),
            attempt = row.int("attempt"),
        ),
        outcome = row.enum<ExtractionInvocationOutcome>("outcome"),
        configuredService = row.strOrNull("configuredService"),
        startedAt = row.strOrNull("startedAt")?.let(Instant::parse),
        finishedAt = row.strOrNull("finishedAt")?.let(Instant::parse),
        usage = deserializeUsage(row.strOrNull("usage")),
        providerResponse = deserializeProviderResponse(row.strOrNull("providerResponse")),
    )

    /**
     * A digest of one attempt's whole payload, computed from the same map [bindMap] builds.
     *
     * Once an attempt is terminal, a later write for the same id is a replay exactly when this
     * digest matches the string an earlier write stored — compared verbatim, the same rule
     * [ExtractionRunTransition.fingerprint] follows for a run's own terminal write, applied one
     * level down. Comparing the digest sidesteps the type drift a database round-trip can
     * introduce — Neo4j hands an `Int` back as a `Long`, say — since both sides of the comparison
     * come straight from a Kotlin record, always, independent of whatever a query happened to
     * return.
     *
     * Digested through [ExtractionRunFingerprint.ofFields], for the reason [headerFingerprint]
     * gives one level up: a raw JSON serialization of [bindMap] would move with the serializer, and
     * this digest is compared verbatim against a string a past write stored, so it has to be a
     * function of the record's fields alone.
     */
    fun fingerprint(record: ExtractionInvocationRecord): String = ExtractionRunFingerprint.ofFields(
        ExtractionRunFingerprint.INVOCATION_VERSION,
        bindMap(record).mapValues { (_, value) -> value?.toString() },
    )
}

// ---- instants ----

/**
 * Writes an instant as the three properties described on [ExtractionRunRowMapper], or removes all
 * three when there is no instant.
 *
 * All three move together. A node carrying a `finishedAt` string and no epoch second would sort as
 * though it had never finished.
 */
private fun MutableMap<String, Any?>.putInstant(field: String, instant: Instant?) {
    put(field, instant?.toString())
    put(field + "EpochSecond", instant?.epochSecond)
    put(field + "Nano", instant?.nano)
}

// ---- JSON encodings ----

/**
 * Source revisions as `[{"sourceKey": ..., "sourceRevision": ...}, ...]`, in the order the run read
 * them.
 *
 * The order is data: the contract says a run records which revisions it read in the order it read
 * them, and [ExtractionRun] compares the lists element by element. Nothing here sorts.
 */
private fun serializeSourceRevisions(revisions: List<SourceRevisionRef>): String =
    objectMapper.writeValueAsString(
        revisions.map { linkedMapOf("sourceKey" to it.sourceKey, "sourceRevision" to it.sourceRevision) },
    )

private fun deserializeSourceRevisions(serialized: String): List<SourceRevisionRef> =
    jsonObjects(serialized, "sourceRevisions").map {
        SourceRevisionRef(
            sourceKey = it.jsonStr("sourceRevisions", "sourceKey"),
            sourceRevision = it.jsonStr("sourceRevisions", "sourceRevision"),
        )
    }

/**
 * Failures as a list of objects, in the order the run recorded them, each holding eight fields: the
 * classified code, the stage, the provider's status, the two halves of one measure, when it
 * happened, and the two halves of the attempt it names.
 *
 * **Every field is a code, a number or a timestamp.** [ExtractionFailure] carries no `String` and no
 * `Throwable`, so there is nothing free-text to write, and this writer names its fields one at a
 * time, so a field added to the failure record cannot arrive here on its own. A text field could
 * only get into a stored row through an edit to the list below, and
 * `DrivineExtractionRunStoreIntegrationTest` round-trips a run carrying failures and compares the
 * stored object's keys against an allowlist it states itself, so such an edit fails the build.
 *
 * The shape is flat. A failure's measure and the attempt it names are two fields each, flattened
 * into the same object, which puts every key a stored failure can carry at one level where that
 * exact-set comparison can see all of them at once. Every optional field is always present and
 * written as a null when it has no value, so a bare failure and a fully populated one store the
 * same keys. Both halves of each pair are
 * written together or not at all, and [deserializeFailures] refuses a stored object holding half of
 * one.
 *
 * **A reader tolerates a key it does not recognise.** [deserializeFailures] asks the stored object
 * for the fields it knows about and ignores anything else sitting beside them, so a node written by
 * a later build, or corrupted by something outside DICE, still reads back as the fields this build
 * understands. Refusing a whole run's failure list over one unexpected key would make an audit
 * record unreadable at the moment someone needs to read it. Keeping DICE's own writer from putting a
 * stray key there is the allowlist test's job; this is what a reader does when it meets one anyway,
 * and the two are separate concerns.
 */
private fun serializeFailures(failures: List<ExtractionFailure>): String =
    objectMapper.writeValueAsString(
        failures.map { failure ->
            linkedMapOf<String, Any?>(
                "code" to failure.code.name,
                "stage" to failure.stage?.name,
                "providerStatus" to failure.providerStatus,
                "measureQuantity" to failure.measure?.quantity?.name,
                "measureValue" to failure.measure?.value,
                "at" to failure.at.toString(),
                "invocationIndex" to failure.invocation?.invocationIndex,
                "attempt" to failure.invocation?.attempt,
            )
        },
    )

private fun deserializeFailures(serialized: String): List<ExtractionFailure> =
    jsonObjects(serialized, "failures").map { fields ->
        val quantity = fields.jsonStrOrNull("measureQuantity")
            ?.let { jsonEnum<ExtractionFailureQuantity>(it, "failures", "measureQuantity") }
        val value = fields.jsonLongOrNull("failures", "measureValue")
        require((quantity == null) == (value == null)) {
            "an entry of the stored 'failures' holds half a measure: a quantity and a value are " +
                "written together or not at all"
        }

        val invocationIndex = fields.jsonIntOrNull("failures", "invocationIndex")
        val attempt = fields.jsonIntOrNull("failures", "attempt")
        require((invocationIndex == null) == (attempt == null)) {
            "an entry of the stored 'failures' holds half an invocation id: an index and an " +
                "attempt are written together or not at all"
        }

        ExtractionFailure(
            code = jsonEnum<ExtractionFailureCode>(fields.jsonStr("failures", "code"), "failures", "code"),
            stage = fields.jsonStrOrNull("stage")
                ?.let { jsonEnum<ExtractionFailureStage>(it, "failures", "stage") },
            providerStatus = fields.jsonIntOrNull("failures", "providerStatus"),
            measure = quantity?.let { ExtractionFailureMeasure(it, value!!) },
            at = Instant.parse(fields.jsonStr("failures", "at")),
            invocation = invocationIndex?.let { ExtractionInvocationId(it, attempt!!) },
        )
    }

/**
 * The requested model configuration as one JSON object with every field present, null included.
 *
 * A configuration with all its fields unset is a real configuration and has to stay distinct from
 * having none — the run asked for the default rather than not asking. Absence of the property is
 * "no configuration"; a `{}`-shaped object with null fields is "the default configuration". Eleven
 * scalar properties on the node would collapse the two.
 *
 * `timeout` is an ISO-8601 duration string. `Duration.parse` inverts `Duration.toString` exactly,
 * and a nanosecond count would be a number nobody reading the node could interpret.
 */
private fun serializeRequestedModel(config: ExtractionRequestedModelConfig?): String? = config?.let {
    objectMapper.writeValueAsString(
        linkedMapOf(
            "modelRole" to it.modelRole,
            "requestedModel" to it.requestedModel,
            "temperature" to it.temperature,
            "topP" to it.topP,
            "topK" to it.topK,
            "maxTokens" to it.maxTokens,
            "presencePenalty" to it.presencePenalty,
            "frequencyPenalty" to it.frequencyPenalty,
            "thinkingFingerprint" to it.thinkingFingerprint,
            "selectionFingerprint" to it.selectionFingerprint,
            "timeout" to it.timeout?.toString(),
        ),
    )
}

private fun deserializeRequestedModel(serialized: String?): ExtractionRequestedModelConfig? {
    val fields = jsonObject(serialized, "requestedModel") ?: return null
    return ExtractionRequestedModelConfig(
        modelRole = fields.jsonStrOrNull("modelRole"),
        requestedModel = fields.jsonStrOrNull("requestedModel"),
        temperature = fields.jsonDoubleOrNull("requestedModel", "temperature"),
        topP = fields.jsonDoubleOrNull("requestedModel", "topP"),
        topK = fields.jsonIntOrNull("requestedModel", "topK"),
        maxTokens = fields.jsonIntOrNull("requestedModel", "maxTokens"),
        presencePenalty = fields.jsonDoubleOrNull("requestedModel", "presencePenalty"),
        frequencyPenalty = fields.jsonDoubleOrNull("requestedModel", "frequencyPenalty"),
        thinkingFingerprint = fields.jsonStrOrNull("thinkingFingerprint"),
        selectionFingerprint = fields.jsonStrOrNull("selectionFingerprint"),
        timeout = fields.jsonStrOrNull("timeout")?.let(Duration::parse),
    )
}

/** Observed token usage, for the same reason [serializeRequestedModel] is one object: empty is real. */
private fun serializeUsage(usage: ExtractionModelUsage?): String? = usage?.let {
    objectMapper.writeValueAsString(
        linkedMapOf(
            "inputTokens" to it.inputTokens,
            "outputTokens" to it.outputTokens,
            "totalTokens" to it.totalTokens,
            "cachedInputTokens" to it.cachedInputTokens,
            "reasoningTokens" to it.reasoningTokens,
        ),
    )
}

private fun deserializeUsage(serialized: String?): ExtractionModelUsage? {
    val fields = jsonObject(serialized, "usage") ?: return null
    return ExtractionModelUsage(
        inputTokens = fields.jsonIntOrNull("usage", "inputTokens"),
        outputTokens = fields.jsonIntOrNull("usage", "outputTokens"),
        totalTokens = fields.jsonIntOrNull("usage", "totalTokens"),
        cachedInputTokens = fields.jsonIntOrNull("usage", "cachedInputTokens"),
        reasoningTokens = fields.jsonIntOrNull("usage", "reasoningTokens"),
    )
}

/** What the provider said about its own answer. Same empty-is-real rule. */
private fun serializeProviderResponse(facts: ExtractionProviderResponseFacts?): String? = facts?.let {
    objectMapper.writeValueAsString(
        linkedMapOf(
            "responseModel" to it.responseModel,
            "responseId" to it.responseId,
            "finishReason" to it.finishReason,
            "systemFingerprint" to it.systemFingerprint,
        ),
    )
}

private fun deserializeProviderResponse(serialized: String?): ExtractionProviderResponseFacts? {
    val fields = jsonObject(serialized, "providerResponse") ?: return null
    return ExtractionProviderResponseFacts(
        responseModel = fields.jsonStrOrNull("responseModel"),
        responseId = fields.jsonStrOrNull("responseId"),
        finishReason = fields.jsonStrOrNull("finishReason"),
        systemFingerprint = fields.jsonStrOrNull("systemFingerprint"),
    )
}

// ---- strict accessors ----

/**
 * Reads a property that must be there, and blows up naming it if it is not.
 *
 * Returning `""` for a missing property would let a node with no `runId` come back as a real-looking
 * run named `""`, and the store's skip-the-unreadable-row guard would never fire for the most likely
 * kind of corruption there is. Throwing is what gives that guard something to catch.
 */
private fun Map<*, *>.str(key: String): String =
    this[key]?.toString() ?: throw IllegalArgumentException("required property '$key' is missing from the stored node")

/**
 * Reads a property whose absence means the run declared none — no profile, no experiment label, no
 * finish time. Everything else goes through [str].
 */
private fun Map<*, *>.strOrNull(key: String): String? = this[key]?.toString()

/** Reads a required whole number. Neo4j hands integers back as `Long`, so the type is widened. */
private fun Map<*, *>.int(key: String): Int = when (val value = this[key]) {
    null -> throw IllegalArgumentException("required property '$key' is missing from the stored node")
    is Number -> value.toInt()
    else -> throw IllegalArgumentException(
        "property '$key' is a ${value.javaClass.simpleName} where a number was expected"
    )
}

/** Reads a required whole number as a `Long`, for the one property — `version` — wide enough to need it. */
private fun Map<*, *>.long(key: String): Long = when (val value = this[key]) {
    null -> throw IllegalArgumentException("required property '$key' is missing from the stored node")
    is Number -> value.toLong()
    else -> throw IllegalArgumentException(
        "property '$key' is a ${value.javaClass.simpleName} where a number was expected"
    )
}

/**
 * Reads a stored enum constant by name.
 *
 * By name, never by ordinal: an ordinal re-points at a different constant the moment someone inserts
 * a value into the enum, and these enums are still moving. A name this build does not have throws,
 * so a node written by a later build is skipped with a message rather than read as something else.
 */
private inline fun <reified E : Enum<E>> Map<*, *>.enum(key: String): E {
    val stored = str(key)
    return enumValues<E>().firstOrNull { it.name == stored } ?: throw IllegalArgumentException(
        "property '$key' is '$stored', which is not a known ${E::class.simpleName} — the node was " +
            "written by a different version of the run model"
    )
}

// ---- strict accessors over decoded JSON ----

/** Parses a stored JSON array of objects, refusing anything that is not one. */
private fun jsonObjects(serialized: String, field: String): List<Map<*, *>> {
    if (serialized.isEmpty()) return emptyList()
    val parsed = objectMapper.readValue(serialized, Any::class.java)
    val elements = parsed as? List<*> ?: throw IllegalArgumentException(
        "the stored '$field' is a ${parsed?.javaClass?.simpleName ?: "null"} where a list was expected"
    )
    return elements.map { element ->
        element as? Map<*, *> ?: throw IllegalArgumentException(
            "the stored '$field' holds a ${element?.javaClass?.simpleName ?: "null"} where an object was expected"
        )
    }
}

/** Parses a stored JSON object, or returns null when the property was absent. */
private fun jsonObject(serialized: String?, field: String): Map<*, *>? {
    if (serialized.isNullOrEmpty()) return null
    val parsed = objectMapper.readValue(serialized, Any::class.java)
    return parsed as? Map<*, *> ?: throw IllegalArgumentException(
        "the stored '$field' is a ${parsed?.javaClass?.simpleName ?: "null"} where an object was expected"
    )
}

private fun Map<*, *>.jsonStr(field: String, name: String): String =
    this[name]?.toString() ?: throw IllegalArgumentException(
        "an entry of the stored '$field' is missing its '$name'"
    )

private fun Map<*, *>.jsonStrOrNull(name: String): String? = this[name]?.toString()

private fun Map<*, *>.jsonIntOrNull(field: String, name: String): Int? = when (val value = this[name]) {
    null -> null
    is Number -> value.toInt()
    else -> throw IllegalArgumentException(
        "the '$name' of the stored '$field' is a ${value.javaClass.simpleName} where a number was expected"
    )
}

/** Reads an optional whole number wide enough for a measure, which counts bytes and milliseconds. */
private fun Map<*, *>.jsonLongOrNull(field: String, name: String): Long? = when (val value = this[name]) {
    null -> null
    is Number -> value.toLong()
    else -> throw IllegalArgumentException(
        "the '$name' of the stored '$field' is a ${value.javaClass.simpleName} where a number was expected"
    )
}

private fun Map<*, *>.jsonDoubleOrNull(field: String, name: String): Double? = when (val value = this[name]) {
    null -> null
    is Number -> value.toDouble()
    else -> throw IllegalArgumentException(
        "the '$name' of the stored '$field' is a ${value.javaClass.simpleName} where a number was expected"
    )
}

private inline fun <reified E : Enum<E>> jsonEnum(stored: String, field: String, name: String): E =
    enumValues<E>().firstOrNull { it.name == stored } ?: throw IllegalArgumentException(
        "the '$name' of the stored '$field' is '$stored', which is not a known ${E::class.simpleName}"
    )
