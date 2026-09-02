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
 * Node labels written by infrastructure libraries that Dice runs on top of, excluding them from
 * whole-graph drift observation.
 *
 * Infrastructure libraries own these labels and write them as part of their own bookkeeping.
 * DICE's schema definitions cannot declare them — the library owns them entirely — so every
 * whole-graph observation would report them as undeclared drift on every run if left unexcluded.
 * This one stays a hand-kept list for exactly that reason: everything else dice excludes is derived
 * from a schema dice compiled, and a library's own bookkeeping has no such schema to derive from.
 *
 * Dice's own bookkeeping edges are decided by shape (a `sourcePropositions` marker on edges), while
 * these are decided by name. An infrastructure library's bookkeeping is declared nowhere in DICE's
 * schema, so there is no shape to recognize it by.
 *
 * Currently includes `_DrivineSchema`, written by the Drivine library itself.
 */
val INFRASTRUCTURE_LABELS: Set<String> = setOf(
    "_DrivineSchema",
)

/**
 * Drivine / Neo4j implementation of [ObservedSchemaSource]: asks a live graph what it contains, so a
 * `DeclaredObservedDiffer` can compare it against what was declared.
 *
 * There are two observation paths, because the database offers no single query that answers both:
 *
 * - **Whole graph** (`contextId == null`) reads the database's own catalogue, `db.labels()` and
 *   `db.relationshipTypes()`, keeps the labels that carry at least one node, and subtracts what dice
 *   owns from both sides. Ownership goes by node shape; see [DiceOwnedSchema]. A dice label the
 *   domain is also using stays in the observation, so an undeclared type can still be reported.
 *
 *   It then asks a second question, and reports the answer in its own set: the distinct
 *   `Mention.type` values on dice's own propositions, across the whole graph, returned as
 *   [ObservedSchema.mentionTypeNames]. A mention type is what an extractor claimed a span was, and
 *   it becomes a graph label only when something projects it, so a graph can hold live propositions
 *   mentioning `Ghost` while `db.labels()` has never heard of it. Reading labels alone left that
 *   type invisible to every unscoped check, which is what this query fixes.
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
 * Mention types are reported as extraction wrote them, on both paths. Dice's ownership rules cover
 * Neo4j labels, while a mention's `type` is a domain type name an extractor produced, so the two
 * live in different namespaces and subtracting one from the other would hide real drift from an app
 * governing a type called `Source`.
 *
 * ## Two kinds of name, kept apart
 *
 * The whole-graph observation answers with labels in [ObservedSchema.entityTypeNames], tagged
 * [ObservedSchema.EntityTypeBasis.GRAPH_LABELS], and mention types in
 * [ObservedSchema.mentionTypeNames]. The differ then judges each by its own rule: a label against
 * every label a declared type carries, so an inherited parent label of a governed type reads as
 * declared, and a mention type against declared type names and their declared former names alone.
 * Merging the two would have to pick one rule for both, and picking the label rule reopens what the
 * mention rule exists to close — a mention typed `Agent` passing under a schema that governs
 * `Person` with parent label `Agent` and declares no `Agent` type. An unscoped check and a
 * context-scoped one now read mention types the same strict way.
 *
 * ## A label with no nodes is no observation
 *
 * The whole-graph label side counts only labels carrying at least one node. Neo4j's `db.labels()`
 * is a catalogue of label *tokens*, and a token is minted the moment a constraint or an index names
 * a label, before any node wears it — probed directly against the `neo4j:2026.05` image this runs
 * on, where a uniqueness constraint on an empty label puts that label in `db.labels()` for good.
 * Constraint DDL is schema machinery an application declared; an observation reports what data the
 * graph holds. So a type a host has declared constraints for and never populated is silently clean,
 * which is the honest answer: there is nothing there to have drifted. The check costs one label
 * lookup per label, each stopping at the first node it finds.
 *
 * Two limits follow from working off names and shape:
 *
 * 1. Ownership is decided per label, and never per node. If any node wearing a dice label fails
 *    dice's shape, the whole label stays observed, dice's own nodes included, so a graph mixing a
 *    domain `Source` with dice's own reports `Source` every run until the domain type is declared.
 * 2. Deciding it costs a scan of dice's own labels on every unscoped observation. Each probe stops
 *    at the first non-conforming node, so it is cheap only where one exists. Context-scoped checks
 *    don't pay it.
 *
 * @param persistenceManager Drivine's handle on the `neo` datasource.
 * @param ownedSchema What this application's own dice storage looks like, built from the
 *   [DiceStorageSchema] beans it registered. Required, with no default, because an observer that
 *   guessed at ownership would report some other slice's store as domain drift forever; whoever
 *   wires an observer has to say what dice owns in that application.
 * @param clock Supplies the snapshot's capture instant. Injectable because that instant ends up in
 *   a drift report's natural key, so a test has to be able to choose whether two checks record one
 *   observation or two.
 */
open class DrivineObservedSchemaSource(
    private val persistenceManager: PersistenceManager,
    private val ownedSchema: DiceOwnedSchema,
    private val clock: Clock = Clock.systemUTC(),
) : ObservedSchemaSource {

    /**
     * Every label some node in the graph actually wears.
     *
     * `db.labels()` alone answers with schema tokens, including labels a constraint or index minted
     * on an empty graph; see the class doc. The subquery turns each token into a question about
     * data, stopping at the first node wearing it, so the answer describes what the graph holds.
     */
    private val labelsWithNodes = """
        CALL db.labels() YIELD label
        CALL (label) {
            MATCH (n:${'$'}(label))
            RETURN n
            LIMIT 1
        }
        RETURN DISTINCT label
    """.trimIndent()

    /**
     * Dice labels the domain has also claimed: those carrying at least one node that fails
     * dice's shape for them. Whatever this returns stays in the observation.
     *
     * One branch per label, each stopping at the first non-conforming node, unioned into a
     * single round trip. Label and property names come from [DiceOwnedSchema], which derives
     * them from the registered storage definitions; nothing caller-derived is assembled in.
     */
    private val labelsClaimedByDomain: String = ownedSchema.nodeShapes.entries
        .joinToString("\nUNION ALL\n") { (label, shape) ->
            val notDiceShaped = shape.joinToString(" OR ") { property -> "n.$property IS NULL" }
            "MATCH (n:$label) WHERE $notDiceShaped RETURN '$label' AS label LIMIT 1"
        }

    /**
     * Bookkeeping relationship types the domain has also claimed: those carrying at least one
     * edge with `sourcePropositions`, the property the graph writer stamps on every edge it
     * projects from domain data.
     */
    private val relationshipTypesClaimedByDomain: String = """
        MATCH ()-[r:${ownedSchema.bookkeepingRelationshipTypes.joinToString("|")}]->()
        WHERE r.sourcePropositions IS NOT NULL
        RETURN DISTINCT type(r)
    """.trimIndent()

    /**
     * Entity types across the whole graph: what dice's own propositions mention, wherever they
     * live.
     *
     * The catalogue this file reads for labels knows nothing about mention types. A type an
     * extractor wrote reaches `db.labels()` only if something projected a node for it, so a
     * graph can hold active propositions mentioning `Ghost` with no `(:Ghost)` node anywhere,
     * and a whole-graph check reading labels alone calls that graph clean. This query asks the
     * propositions themselves.
     *
     * Both ends are held to dice's own shape, so a domain node that happens to wear
     * `:Proposition` or `:Mention` contributes nothing: what comes back is the set of types
     * dice's own extraction recorded. `m.type` is part of the mention shape, so a mention with
     * no type is already excluded by it.
     *
     * Every proposition counts, whatever its status, which is how the context-scoped query
     * reads too. A quarantined proposition still carries the undeclared type it was quarantined
     * for, and a check that stopped reporting it would read clean while the data sits there.
     */
    private val mentionTypesInGraph = """
        MATCH (p:Proposition)-[:HAS_MENTION]->(m:Mention)
        WHERE ${ownedSchema.ownedNodePredicate("p", "Proposition")}
          AND ${ownedSchema.ownedNodePredicate("m", "Mention")}
        RETURN DISTINCT m.type
    """.trimIndent()

    private companion object {

        private const val ALL_RELATIONSHIP_TYPES =
            "CALL db.relationshipTypes() YIELD relationshipType RETURN relationshipType"

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
        // Subtract only the storage the domain has not also claimed. A name both dice and the
        // domain use counts as the domain's here, so it stays observable as drift.
        val hiddenLabels = ownedSchema.labels - queryStrings(labelsClaimedByDomain)
        val hiddenRelationshipTypes =
            ownedSchema.bookkeepingRelationshipTypes - queryStrings(relationshipTypesClaimedByDomain)
        // Two kinds of entity name, in two sets: the labels the graph reports, and the types dice's
        // propositions were extracted with. The mention side needs no subtraction, since the query
        // that produced it already asked dice's own propositions, and it stays out of the label set
        // so the differ can hold it to the mention rule; see the class doc.
        return ObservedSchema(
            entityTypeNames = queryStrings(labelsWithNodes) - hiddenLabels - INFRASTRUCTURE_LABELS,
            relationshipTypeNames = queryStrings(ALL_RELATIONSHIP_TYPES) - hiddenRelationshipTypes,
            capturedAt = clock.instant(),
            entityTypeBasis = ObservedSchema.EntityTypeBasis.GRAPH_LABELS,
            mentionTypeNames = queryStrings(mentionTypesInGraph),
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
