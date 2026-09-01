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
import com.embabel.dice.metamodel.ObservedSchema
import com.embabel.dice.metamodel.ObservedSchemaSource
import org.drivine.manager.PersistenceManager
import org.drivine.query.QuerySpecification
import org.springframework.transaction.annotation.Transactional
import java.time.Clock

/**
 * Every node label dice writes for its own bookkeeping, and the properties that identify a node
 * carrying that label as dice's own.
 *
 * A drift check compares the domain schema an app declared against what a live graph holds. None of
 * these labels belongs to a declared domain schema, so counting them would flag dice's own storage
 * as drift on every run, and would make governance report itself: stamping a version and writing a
 * report both add labels to the graph the next check observes.
 *
 * Exclusion is by shape, so that a domain type called `Source` stays visible. A label is excluded
 * only when the nodes carrying it match dice's shape for it — dice's `Source` nodes carry `key`, its
 * governance nodes carry `schemaName`, and so on. A same-named node that doesn't match keeps the
 * label in the observation, where an undeclared type can still be reported.
 *
 * Each shape is the label's declared uniqueness key, plus properties the node fragment writes
 * unconditionally where those add discrimination. Shapes are kept minimal: one demanding a property
 * dice doesn't always write would make dice's own nodes look foreign and bring back the
 * self-reporting case above.
 *
 * `DiceBookkeepingShapeTest` pins that every label in [CollectorTraceSchema.LABELS] and
 * [MetamodelSchema.LABELS] has an entry here, so adding a node label to either store can't quietly
 * skip this map.
 */
val DICE_BOOKKEEPING_LABEL_SHAPES: Map<String, List<String>> = mapOf(
    // Core persistence: propositions and their mentions, provenance, chunk history.
    "Proposition" to listOf("id", "contextId", "text"),
    "Mention" to listOf("id", "span", "type", "role"),
    "Source" to listOf("key"),
    "ProcessedChunk" to listOf("id"),

    // Lineage records.
    "ProjectionRecord" to listOf("propositionId", "runId", "target"),
    "CollectorRecord" to listOf("propositionId", "runId"),
    "CollectorRun" to listOf("runId"),

    // Collector trace. Uniqueness keys only — the trace store writes these through several
    // statements, and a stricter shape would risk calling its own half-written run foreign.
    "CollectorTraceRun" to listOf("runId"),
    "CollectorCandidateEdge" to listOf("id"),
    "CollectorSignalScore" to listOf("id"),
    "CollectorComponent" to listOf("id"),
    "CollectorDecision" to listOf("id"),
    "CollectorRetired" to listOf("id"),

    // Metamodel governance.
    "MetamodelVersion" to listOf("schemaName", "contentHash"),
    "MetamodelSchemaCounter" to listOf("schemaName"),
    "MetamodelDriftReport" to listOf("schemaName", "versionHash", "capturedAt", "contextKey"),
    "MetamodelDriftReportCounter" to listOf("schemaName"),
)

/** The bookkeeping label names, for callers that only need the names. */
val DICE_BOOKKEEPING_LABELS: Set<String> = DICE_BOOKKEEPING_LABEL_SHAPES.keys

/**
 * Relationship types dice writes for its own bookkeeping, on the same grounds as
 * [DICE_BOOKKEEPING_LABEL_SHAPES] and under the same shape rule.
 *
 * Dice's bookkeeping extends past node labels. `HAS_MENTION` and `DERIVED_FROM` sit on every
 * proposition ever stored, so without this set a whole-graph observation reports them as undeclared
 * relationship drift on every run against a populated graph.
 *
 * These carry no per-type shape because one discriminator covers all of them, and it lives on the
 * edge: every edge the graph writer projects from domain data carries `sourcePropositions`, and no
 * dice bookkeeping edge carries it. The context-scoped path selects on the same marker, so both
 * paths agree.
 */
val DICE_BOOKKEEPING_RELATIONSHIP_TYPES: Set<String> = setOf(
    "HAS_MENTION",
    "DERIVED_FROM",
    "SCORED",
    "RETIRED_IN",
)

/**
 * Drivine / Neo4j implementation of [ObservedSchemaSource]: asks a live graph what it contains, so a
 * `DeclaredObservedDiffer` can compare it against what was declared.
 *
 * There are two observation paths, because the database offers no single query that answers both:
 *
 * - **Whole graph** (`contextId == null`) introspects the database's own catalogue, `db.labels()`
 *   and `db.relationshipTypes()`, and subtracts dice's bookkeeping from both sides. The subtraction
 *   goes by node shape; see [DICE_BOOKKEEPING_LABEL_SHAPES]. A bookkeeping name the domain is also
 *   using stays in the observation, so an undeclared type can still be reported.
 * - **One context** (`contextId != null`) cannot use those procedures: they have no notion of a
 *   context and answer for the whole database. It derives both sides from that context's own data:
 *   - entity types are the distinct `Mention.type` values on that context's propositions;
 *   - relationship types come from the `sourcePropositions` property the graph writer stamps on
 *     every edge it persists, holding the ids of the propositions that produced it. An edge belongs
 *     to a context's set when at least one of those ids names a proposition in that context. It is
 *     a join on that property, and it does not collapse: an edge sourced from two contexts appears
 *     in both. An undeclared relationship type present in a context's data is drift in that context
 *     whoever else produced it.
 *
 * The scoped entity side is unfiltered. Bookkeeping exclusions are Neo4j labels, while a mention's
 * `type` is a domain type name an extractor produced, so the two live in different namespaces and
 * subtracting one from the other would hide real drift from an app governing a type called `Source`.
 *
 * Two limits follow from working off names and shape:
 *
 * 1. Exclusion is decided per label, not per node. If any node wearing a bookkeeping label fails
 *    dice's shape, the whole label stays observed, dice's own nodes included, so a graph mixing a
 *    domain `Source` with dice's own reports `Source` every run until the domain type is declared.
 * 2. Deciding it costs a scan of dice's own labels on every unscoped observation. Each probe stops
 *    at the first non-conforming node, so it is cheap only where one exists. Context-scoped checks
 *    don't pay it.
 *
 * @param persistenceManager Drivine's handle on the `neo` datasource.
 * @param clock Supplies the snapshot's capture instant. Injectable because that instant ends up in
 *   a drift report's natural key, so a test has to be able to choose whether two checks record one
 *   observation or two.
 */
open class DrivineObservedSchemaSource(
    private val persistenceManager: PersistenceManager,
    private val clock: Clock = Clock.systemUTC(),
) : ObservedSchemaSource {

    private companion object {

        private const val ALL_LABELS = "CALL db.labels() YIELD label RETURN label"

        private const val ALL_RELATIONSHIP_TYPES =
            "CALL db.relationshipTypes() YIELD relationshipType RETURN relationshipType"

        /**
         * Bookkeeping labels the domain has also claimed: those carrying at least one node that
         * fails dice's shape for them. Whatever this returns stays in the observation.
         *
         * One branch per label, each stopping at the first non-conforming node, unioned into a
         * single round trip. Label and property names are this file's own compile-time constants;
         * nothing caller-derived is assembled in.
         */
        private val LABELS_CLAIMED_BY_DOMAIN: String = DICE_BOOKKEEPING_LABEL_SHAPES.entries
            .joinToString("\nUNION ALL\n") { (label, shape) ->
                val notDiceShaped = shape.joinToString(" OR ") { property -> "n.$property IS NULL" }
                "MATCH (n:$label) WHERE $notDiceShaped RETURN '$label' AS label LIMIT 1"
            }

        /**
         * Bookkeeping relationship types the domain has also claimed: those carrying at least one
         * edge with `sourcePropositions`, the property the graph writer stamps on every edge it
         * projects from domain data.
         */
        private val RELATIONSHIP_TYPES_CLAIMED_BY_DOMAIN: String = """
            MATCH ()-[r:${DICE_BOOKKEEPING_RELATIONSHIP_TYPES.joinToString("|")}]->()
            WHERE r.sourcePropositions IS NOT NULL
            RETURN DISTINCT type(r)
        """.trimIndent()

        /**
         * Entity types in one context: what its propositions actually mention.
         *
         * `m.type IS NOT NULL` because a null would come back as a missing element rather than a
         * type name and land in the observed set as nothing useful.
         */
        private val MENTION_TYPES_IN_CONTEXT = """
            MATCH (:Proposition {contextId: ${'$'}contextId})-[:HAS_MENTION]->(m:Mention)
            WHERE m.type IS NOT NULL
            RETURN DISTINCT m.type
        """.trimIndent()

        /**
         * Relationship types in one context: every edge at least one of whose source propositions
         * lives there.
         *
         * dice's own edges have no `sourcePropositions` property, so `any(... IN null ...)` is null
         * for them and they drop out here, with no name-based exclusion needed afterwards. The
         * whole-graph path mirrors this shape test.
         */
        private val RELATIONSHIP_TYPES_IN_CONTEXT = """
            MATCH (p:Proposition {contextId: ${'$'}contextId})
            WITH collect(p.id) AS ids
            MATCH ()-[r]->()
            WHERE any(pid IN r.sourcePropositions WHERE pid IN ids)
            RETURN DISTINCT type(r)
        """.trimIndent()
    }

    /**
     * Both branches issue several queries that get assembled into one [ObservedSchema], and this
     * annotation is what keeps them from running as separate implicit transactions: everything below
     * runs inside one Neo4j transaction, the same pattern [DrivineCollectorTraceStore.findEdgesByRun]
     * uses to rehydrate one answer out of more than one query. Where these queries run as separate
     * implicit transactions, a concurrent graph write landing between two of them shows up in only
     * one, the other having already run by the time it committed, and the resulting [ObservedSchema]
     * describes a combination of graph states that existed at no single instant.
     *
     * The guarantee this buys stops short of full snapshot isolation. Neo4j's default isolation
     * level is read committed, a per-row guarantee that applies within a single statement's own
     * execution: a single statement's own result rows are not guaranteed to reflect one coherent instant
     * of the graph either, because a write can commit while that one statement is still streaming
     * its rows, so a query can itself see a non-repeatable, missing, or double read of data it
     * touches more than once during its own execution, on top of the cross-statement residual below.
     * A write that commits while this transaction is still open can reach any statement, or any row
     * within a statement, that is read after that commit, while an earlier read in the same
     * transaction has already returned its own answer from the graph as it stood beforehand. What
     * this annotation buys is narrowing the exposure down to the span of one transaction, and ruling
     * out reads that were never even in the same transaction to begin with; it does not make any one
     * statement's own result set internally coherent. A caller needing a stronger guarantee would need
     * Neo4j's explicit locking, which this observation has no reason to pay for: drift is inherently a
     * live-graph snapshot judged moments after the fact, and an occasional narrow race landing inside
     * one check's transaction is a smaller, self-healing problem — the next check reads it either way.
     * Reads spread across separate transactions carry no time bound between them at all, which is the
     * wider exposure this closes.
     */
    @Transactional(readOnly = true)
    override fun observe(contextId: ContextId?): ObservedSchema =
        if (contextId == null) observeWholeGraph() else observeContext(contextId)

    /**
     * Carries its own [Transactional] annotation, which is why this override exists at all. [ObservedSchemaSource.observe]'s default body, `= observe(null)`, calls
     * `observe(contextId)` on `this` from inside the bean's own compiled code — a self-invocation.
     * Spring's proxy applies `@Transactional` advice only to calls that arrive through the proxy
     * from outside the bean, so a caller invoking the interface's no-argument `observe()` on this
     * class, absent this override, would reach [observeWholeGraph] with no transaction started at
     * all, and its several queries would run as separate implicit transactions again, exactly
     * what annotating the two-argument overload was meant to close.
     */
    @Transactional(readOnly = true)
    override fun observe(): ObservedSchema = observe(null)

    private fun observeWholeGraph(): ObservedSchema {
        // Subtract only the bookkeeping the domain has not also claimed. A name both dice and the
        // domain use counts as the domain's here, so it stays observable as drift.
        val hiddenLabels = DICE_BOOKKEEPING_LABELS - queryStrings(LABELS_CLAIMED_BY_DOMAIN)
        val hiddenRelationshipTypes =
            DICE_BOOKKEEPING_RELATIONSHIP_TYPES - queryStrings(RELATIONSHIP_TYPES_CLAIMED_BY_DOMAIN)
        return ObservedSchema(
            entityTypeNames = queryStrings(ALL_LABELS) - hiddenLabels,
            relationshipTypeNames = queryStrings(ALL_RELATIONSHIP_TYPES) - hiddenRelationshipTypes,
            capturedAt = clock.instant(),
            entityTypeBasis = ObservedSchema.EntityTypeBasis.GRAPH_LABELS,
        )
    }

    private fun observeContext(contextId: ContextId): ObservedSchema {
        val bindings = mapOf("contextId" to contextId.value)
        // Neither side subtracts anything. The scoped queries are already shape-based: mention types
        // are domain names by construction, and the relationship query selects on
        // `sourcePropositions`, which only a projected domain edge carries. Subtracting names on top
        // would drop a domain relationship type spelled `DERIVED_FROM` that the query had already
        // shown to be the domain's.
        //
        // Tagged MENTION_TYPES deliberately: a mention's `type` is domain data an extractor wrote,
        // living in its own namespace apart from graph labels, so a governed type's inherited
        // parent label must stay out of what
        // counts as declared for it. See ObservedSchema.EntityTypeBasis.
        return ObservedSchema(
            entityTypeNames = queryStrings(MENTION_TYPES_IN_CONTEXT, bindings),
            relationshipTypeNames = queryStrings(RELATIONSHIP_TYPES_IN_CONTEXT, bindings),
            capturedAt = clock.instant(),
            entityTypeBasis = ObservedSchema.EntityTypeBasis.MENTION_TYPES,
        )
    }

    /** Run a single-column query and collect its non-null values. */
    private fun queryStrings(statement: String, bindings: Map<String, Any?> = emptyMap()): Set<String> {
        val spec = QuerySpecification.withStatement(statement).bind(bindings).transform(String::class.java)
        return persistenceManager.query(spec).filterNotNull().toSet()
    }
}
