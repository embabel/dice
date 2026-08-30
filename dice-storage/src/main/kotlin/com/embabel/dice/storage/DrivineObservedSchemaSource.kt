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
import java.time.Clock

/**
 * Every node label dice writes for its own bookkeeping, and the properties that identify a node as
 * really being dice's rather than merely wearing the same label.
 *
 * A drift check compares the *domain* schema an app declared against what a live graph actually
 * holds. None of these labels was ever part of anybody's declared domain schema, so counting them
 * would flag dice's own storage machinery as drift on every run — and, worse, would make governance
 * report *itself*, since stamping a version and writing a report both add labels to the very graph
 * the next check observes. A check that fires because the last check ran is noise that never
 * settles.
 *
 * **The shape is here because a name is not a reservation.** Excluding the *name* `Source` would
 * hide a domain type genuinely called `Source` — an undeclared one could then never appear in an
 * unscoped report, which is the failure a drift check exists to prevent, made permanent and silent.
 * So a label is only excluded when the nodes carrying it actually look like dice's: dice's `Source`
 * nodes carry `key`, its governance nodes carry `schemaName`, and so on. A same-named node that
 * doesn't match keeps the label in the observation.
 *
 * Each shape is the label's declared uniqueness key, extended with properties the node fragment
 * writes unconditionally where those add discrimination. Deliberately kept *minimal*: a shape that
 * demanded a property dice doesn't always write would make dice's own nodes look foreign and
 * reintroduce the self-reporting bug, which is the worse direction to be wrong in.
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
 * [DICE_BOOKKEEPING_LABEL_SHAPES] and subject to the same name-is-not-a-reservation rule.
 *
 * Worth stating plainly, because the obvious first version of this class had no such set: dice's
 * bookkeeping is *not* all node labels. `HAS_MENTION` and `DERIVED_FROM` sit on every proposition
 * ever stored, so without this a whole-graph observation reports them as undeclared relationship
 * drift on the very first run against a populated graph, forever.
 *
 * These have no per-type shape because the discriminator is the same for all of them and belongs to
 * the *other* side: every edge the graph writer projects from domain data carries
 * `sourcePropositions`, and no dice bookkeeping edge does. That is the positive marker the
 * context-scoped path already selects on, so the whole-graph path uses it too and the two agree.
 */
val DICE_BOOKKEEPING_RELATIONSHIP_TYPES: Set<String> = setOf(
    "HAS_MENTION",
    "DERIVED_FROM",
    "SCORED",
    "RETIRED_IN",
)

/**
 * Drivine / Neo4j implementation of [ObservedSchemaSource]: asks a live graph what it actually
 * contains, so a `DeclaredObservedDiffer` can hold it up against what was declared.
 *
 * Two genuinely different observation paths, because the database offers no single query that
 * answers both:
 *
 * - **Whole graph** (`contextId == null`) introspects the database's own catalogue — `db.labels()`
 *   and `db.relationshipTypes()` — and subtracts dice's bookkeeping from both sides. The
 *   subtraction is by *shape*, not by name: see [DICE_BOOKKEEPING_LABEL_SHAPES]. A bookkeeping name
 *   the domain is also using stays in the observation, so an undeclared type can still be reported.
 * - **One context** (`contextId != null`) cannot use those procedures at all: they have no notion
 *   of a context and would answer for the whole database. It derives both sides from that context's
 *   own data instead:
 *   - entity types are the distinct `Mention.type` values on that context's propositions;
 *   - relationship types come from the `sourcePropositions` property the graph writer stamps on
 *     every edge it persists — the ids of the propositions that produced it. An edge belongs to a
 *     context's set when at least one of those ids names a proposition in that context. That is a
 *     join on a property, not a tag on the edge, and it deliberately does not collapse: an edge
 *     sourced from two contexts appears in both. An undeclared relationship type present in your
 *     context's data is drift in your context regardless of who else also produced it.
 *
 * The scoped entity side is deliberately *not* filtered at all. Those are Neo4j labels; a mention's
 * `type` is a domain type name an extractor produced, so the two live in different namespaces and
 * subtracting one from the other would only ever hide real drift from an app that happens to govern
 * a type called `Source`.
 *
 * **Two honest limits, neither resolvable from names and shape alone.** First, exclusion is decided
 * per *label*, not per node: if any node wearing a bookkeeping label fails dice's shape, the whole
 * label stays observed, dice's own nodes included. That direction is chosen on purpose — a
 * spuriously reported type is visible and dismissable, a silently hidden one is neither — but it
 * does mean a graph mixing a domain `Source` with dice's own will report `Source` every run until
 * the domain type is declared. Second, deciding this costs a scan of dice's own labels on every
 * unscoped observation (each probe stops at the first non-conforming node, so it is cheap only when
 * one exists). Context-scoped checks never pay it, and on a very large graph they are the ones to
 * schedule.
 *
 * @param persistenceManager Drivine's handle on the `neo` datasource.
 * @param clock Supplies the snapshot's capture instant. Injectable because that instant ends up in
 *   a drift report's natural key, so a test that needs two checks to be one observation — or two —
 *   has to be able to choose it.
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
         * Bookkeeping labels the domain has also claimed — those carrying at least one node that
         * does *not* match dice's shape for them. Whatever this returns stays in the observation.
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
         * Bookkeeping relationship types the domain has also claimed — those carrying at least one
         * edge with `sourcePropositions`, which is the property the graph writer stamps on every
         * edge it projects from domain data and no dice bookkeeping edge has.
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
         * for them and they drop out here rather than needing a name-based exclusion afterwards.
         * This is the shape test the whole-graph path mirrors.
         */
        private val RELATIONSHIP_TYPES_IN_CONTEXT = """
            MATCH (p:Proposition {contextId: ${'$'}contextId})
            WITH collect(p.id) AS ids
            MATCH ()-[r]->()
            WHERE any(pid IN r.sourcePropositions WHERE pid IN ids)
            RETURN DISTINCT type(r)
        """.trimIndent()
    }

    override fun observe(contextId: ContextId?): ObservedSchema =
        if (contextId == null) observeWholeGraph() else observeContext(contextId)

    private fun observeWholeGraph(): ObservedSchema {
        // Subtract only the bookkeeping the domain has *not* also claimed. A name dice uses and the
        // domain also uses is the domain's for observation purposes, because failing to report an
        // undeclared type is the one failure a drift check cannot recover from.
        val hiddenLabels = DICE_BOOKKEEPING_LABELS - queryStrings(LABELS_CLAIMED_BY_DOMAIN)
        val hiddenRelationshipTypes =
            DICE_BOOKKEEPING_RELATIONSHIP_TYPES - queryStrings(RELATIONSHIP_TYPES_CLAIMED_BY_DOMAIN)
        return ObservedSchema(
            entityTypeNames = queryStrings(ALL_LABELS) - hiddenLabels,
            relationshipTypeNames = queryStrings(ALL_RELATIONSHIP_TYPES) - hiddenRelationshipTypes,
            capturedAt = clock.instant(),
        )
    }

    private fun observeContext(contextId: ContextId): ObservedSchema {
        val bindings = mapOf("contextId" to contextId.value)
        // Neither side subtracts anything. The scoped queries are already shape-based: mention types
        // are domain names by construction, and the relationship query selects on
        // `sourcePropositions`, which only a projected domain edge carries. Subtracting names on top
        // would be the bug this class exists to avoid — it would drop a domain relationship type
        // that happens to be spelled `DERIVED_FROM` even though the query proved it was the
        // domain's.
        return ObservedSchema(
            entityTypeNames = queryStrings(MENTION_TYPES_IN_CONTEXT, bindings),
            relationshipTypeNames = queryStrings(RELATIONSHIP_TYPES_IN_CONTEXT, bindings),
            capturedAt = clock.instant(),
        )
    }

    /** Run a single-column query and collect its non-null values. */
    private fun queryStrings(statement: String, bindings: Map<String, Any?> = emptyMap()): Set<String> {
        val spec = QuerySpecification.withStatement(statement).bind(bindings).transform(String::class.java)
        return persistenceManager.query(spec).filterNotNull().toSet()
    }
}
