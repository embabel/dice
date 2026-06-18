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
package com.embabel.dice.agent

import com.embabel.agent.api.tool.Tool
import com.embabel.common.core.types.TextSimilaritySearchRequest
import com.embabel.dice.proposition.Proposition
import com.embabel.dice.proposition.PropositionQuery
import com.embabel.dice.proposition.PropositionRepository
import org.slf4j.LoggerFactory

/**
 * The retrieval + rendering engine behind [Memory]'s search tool, kept
 * separate so [Memory] itself stays focused on construction, eager
 * loading, scoping, and the tool/reference contract.
 *
 * [search] runs hybrid retrieval over the scoped propositions, in three
 * tiers:
 *  1. **vector** similarity — semantic; carries question-shaped queries;
 *  2. **keyword** by term overlap — lexical; catches exact names / rare terms;
 *  3. **related** entity expansion — when 1+2 come back thin, more
 *     propositions about the SAME entities the direct hits mention.
 * Results are unioned and each line is tagged with the probe(s) that
 * found it (`[vector]` / `[keyword]` / `[related]`). When a
 * [ProvenanceResolver] is wired, every line also carries its source and
 * the entity ids it mentions. Tiers are merged with **Reciprocal Rank
 * Fusion** — a proposition's final score sums `1/(RRF_K + rank)` over
 * every tier that found it, so a hit corroborated by several probes
 * outranks a single probe's high-but-lone hit. Ties keep tier order
 * (vector, then keyword, then related).
 *
 * @param eagerIds propositions already surfaced eagerly in the system
 *   prompt — removed from results so the LLM only sees new information.
 */
internal class MemoryRetriever(
    private val repository: PropositionRepository,
    private val provenanceResolver: ProvenanceResolver?,
    private val topic: String,
    private val eagerIds: Set<String>,
) {
    private val logger = LoggerFactory.getLogger(MemoryRetriever::class.java)

    /** Hybrid search for a freeform [query] within the [base] scope. */
    fun search(query: String, base: PropositionQuery, limit: Int): Tool.Result {
        val ordered = LinkedHashMap<String, ProbeHit>()

        // Tier 1+2 — direct probes. Vector (similarity order) and
        // keyword (term overlap); a proposition found by both is tagged
        // with both and accumulates RRF score from each.
        fuse(ordered, vectorProbe(query, base, limit), "vector")
        fuse(ordered, keywordProbe(query, base, limit), "keyword")

        // Tier 3 — when the direct probes are thin, widen to other
        // propositions about the same entities the hits mention.
        if (ordered.values.count { it.prop.id !in eagerIds } < limit) {
            fuse(ordered, expandByEntities(ordered.values, base, limit), "related")
        }

        // Reciprocal Rank Fusion across the tiers: consensus hits (found
        // by more than one probe) outrank a single probe's high-but-lone
        // hit. Stable sort, so equal scores keep tier insertion order.
        val hits = ordered.values
            .filter { it.prop.id !in eagerIds }
            .sortedByDescending { it.rrf }
            .take(limit)
        return if (hits.isEmpty()) noMatch(query, base) else renderHits(query, hits)
    }

    /** List all in-scope memories by confidence (no query supplied). */
    fun listAll(base: PropositionQuery, limit: Int): Tool.Result {
        val results = repository.query(base.orderedByEffectiveConfidence().withLimit(limit))
            .filter { it.id !in eagerIds }
            .take(limit)

        if (results.isEmpty()) {
            return Tool.Result.text(
                if (eagerIds.isNotEmpty()) "No additional memories beyond those already provided."
                else "No memories stored yet."
            )
        }

        val provenance = resolveProvenance(results.map { it.id })
        val text = buildString {
            appendLine("All memories (${results.size}):")
            results.forEach { prop -> appendMemoryLine("- ", prop, provenance) }
        }.trimEnd()
        return Tool.Result.text(text)
    }

    /**
     * Fold one probe's ranked result into the union: dedupe by
     * proposition id, record the probe tag, and accumulate its
     * Reciprocal Rank Fusion contribution `1/(RRF_K + rank)` (rank is
     * the 1-based position in this probe's list). Summing across probes
     * rewards consensus without needing the probes' scores to share a
     * scale.
     */
    private fun fuse(into: LinkedHashMap<String, ProbeHit>, ranked: List<Proposition>, tag: String) {
        ranked.forEachIndexed { i, prop ->
            val hit = into.getOrPut(prop.id) { ProbeHit(prop, mutableSetOf()) }
            hit.sources += tag
            hit.rrf += 1.0 / (RRF_K + i + 1)
        }
    }

    /** Tier 1 — vector similarity probe. */
    private fun vectorProbe(query: String, base: PropositionQuery, limit: Int): List<Proposition> =
        try {
            repository.findSimilarWithScores(
                TextSimilaritySearchRequest(query = query, similarityThreshold = 0.0, topK = limit),
                base,
            ).map { it.match }
        } catch (t: Throwable) {
            logger.warn("vector probe failed for '{}': {}", query, t.message)
            emptyList()
        }

    /**
     * Tier 2 — keyword probe by TERM OVERLAP, not whole-string
     * substring: a phrase query ("evidence I'm interested in Canva")
     * never substring-matches a proposition, but its salient term
     * ("Canva") will. Score candidates by how many distinct query
     * tokens they contain and keep the best. No stopword list —
     * overlap-count ranking naturally floats the propositions sharing
     * the rare, meaningful tokens.
     */
    private fun keywordProbe(query: String, base: PropositionQuery, limit: Int): List<Proposition> {
        val tokens = tokenize(query)
        if (tokens.isEmpty()) return emptyList()
        return repository.query(base.orderedByEffectiveConfidence().withLimit(limit * 10))
            .map { p -> p to tokens.count { t -> p.text.contains(t, ignoreCase = true) } }
            .filter { it.second > 0 }
            .sortedByDescending { it.second }
            .map { it.first }
            .take(limit)
    }

    /**
     * Tier 3 — neighbourhood recall: more propositions about the SAME
     * entities the current hits mention, done purely through the
     * proposition store (no external graph). Used only when the direct
     * probes come back thin.
     */
    private fun expandByEntities(
        hits: Collection<ProbeHit>,
        base: PropositionQuery,
        limit: Int,
    ): List<Proposition> {
        val seedEntityIds = hits
            .flatMap { hit -> hit.prop.mentions.mapNotNull { it.resolvedId } }
            .filter { it.isNotBlank() }
            .distinct()
            .take(MAX_EXPANSION_SEEDS)
        if (seedEntityIds.isEmpty()) return emptyList()
        return try {
            repository.query(
                base.withAnyEntityIds(seedEntityIds)
                    .orderedByEffectiveConfidence()
                    .withLimit(limit * 3)
            )
        } catch (t: Throwable) {
            logger.warn("entity expansion failed: {}", t.message)
            emptyList()
        }
    }

    /** Empty-result message that nudges the LLM to try another query. */
    private fun noMatch(query: String, base: PropositionQuery): Tool.Result {
        val total = repository.query(base).size
        val tail = if (total > 0) " — $total memories are stored about $topic." else "."
        return Tool.Result.text("No memories matched '$query'. Try rephrasing or a broader query$tail")
    }

    /** Render the unioned hits, each tagged with its probe(s) and provenance. */
    private fun renderHits(query: String, hits: List<ProbeHit>): Tool.Result {
        val provenance = resolveProvenance(hits.map { it.prop.id })
        val text = buildString {
            appendLine("Memories about '$query' (${hits.size}):")
            hits.forEach { hit ->
                appendMemoryLine("- [${hit.sources.sorted().joinToString(",")}] ", hit.prop, provenance)
            }
        }.trimEnd()
        return Tool.Result.text(text)
    }

    /**
     * Split a query into lexical tokens for the keyword probe:
     * lower-cased, de-duplicated runs of Unicode letters/digits of
     * length >= [MIN_TOKEN_LEN]. No stopword list (language-agnostic);
     * overlap-count ranking in [search] handles common words.
     */
    private fun tokenize(query: String): List<String> =
        query.lowercase()
            .split(Regex("[^\\p{L}\\p{N}]+"))
            .filter { it.length >= MIN_TOKEN_LEN }
            .distinct()

    /**
     * Resolve provenance for a result set in one batch. Returns an
     * empty map when no resolver is wired or the lookup fails — memory
     * still returns its propositions, just without source annotations.
     */
    private fun resolveProvenance(ids: List<String>): Map<String, List<String>> {
        val resolver = provenanceResolver ?: return emptyMap()
        if (ids.isEmpty()) return emptyMap()
        return try {
            resolver.resolveSources(ids)
        } catch (t: Throwable) {
            logger.warn("provenance resolution failed: {}", t.message)
            emptyMap()
        }
    }

    /**
     * Append one proposition line. Two compact suffixes are added when
     * available:
     *  - `— source: …` — provenance (where the fact came from), so the
     *    LLM can cite it;
     *  - `— entities: name (id); …` — the resolved entities this
     *    proposition mentions, so the LLM has durable handles to drill
     *    into (via find_entity / a Cypher anchor) without re-searching.
     * Both are capped and truncated so a busy proposition can't blow up
     * the result.
     */
    private fun StringBuilder.appendMemoryLine(
        prefix: String,
        prop: Proposition,
        provenance: Map<String, List<String>>,
    ) {
        val sources = provenance[prop.id].orEmpty()
            .filter { it.isNotBlank() }
            .distinct()
            .take(MAX_SOURCES_PER_PROP)
            .joinToString("; ") { it.trim().take(MAX_SOURCE_CHARS) }
        val entities = prop.mentions
            .filter { !it.resolvedId.isNullOrBlank() }
            .distinctBy { it.resolvedId }
            .take(MAX_ENTITIES_PER_PROP)
            .joinToString("; ") { "${it.span.trim().take(MAX_ENTITY_CHARS)} (${it.resolvedId})" }
        appendLine(
            buildString {
                append(prefix); append(prop.text)
                if (sources.isNotBlank()) append(" — source: $sources")
                if (entities.isNotBlank()) append(" — entities: $entities")
            }
        )
    }

    private data class ProbeHit(
        val prop: Proposition,
        val sources: MutableSet<String>,
        var rrf: Double = 0.0,
    )

    private companion object {
        /**
         * Reciprocal Rank Fusion damping constant. The standard value
         * (60) flattens the curve so deep-but-corroborated hits still
         * matter; raising it weights consensus over absolute rank.
         */
        const val RRF_K = 60

        /** Min token length for the keyword (term-overlap) probe. */
        const val MIN_TOKEN_LEN = 3

        /** Max seed entities used for the entity-expansion tier. */
        const val MAX_EXPANSION_SEEDS = 4

        /** Per-proposition provenance display caps. */
        const val MAX_SOURCES_PER_PROP = 2
        const val MAX_SOURCE_CHARS = 80

        /** Per-proposition entity-handle display caps. */
        const val MAX_ENTITIES_PER_PROP = 4
        const val MAX_ENTITY_CHARS = 40
    }
}
