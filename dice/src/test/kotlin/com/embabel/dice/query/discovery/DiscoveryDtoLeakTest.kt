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
package com.embabel.dice.query.discovery

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.reflect.KClass
import kotlin.reflect.KType
import kotlin.reflect.full.memberProperties

/**
 * Gate enforcing the no-leak contract: no store / RAG / graph / domain type may surface through any
 * discovery DTO. Reflectively walks every public property type of every outward DTO (recursively
 * into nested DTO types and generic type arguments) and asserts no encountered type's fully-qualified
 * name matches a forbidden pattern.
 *
 * The test fails if any future DTO field reintroduces a leaking type.
 */
class DiscoveryDtoLeakTest {

    /** The outward DTOs that form the discovery surface boundary. */
    private val rootDtos: List<KClass<*>> = listOf(
        DiscoveryResult::class,
        PropositionSummaryDto::class,
        EntityMentionSummaryDto::class,
        PathDto::class,
        NeighborhoodDto::class,
        DiscoveryProvenanceDto::class,
        LineageDto::class,
        ProjectionHealthDto::class,
        TargetHealthDto::class,
        CollectorDryRunDto::class,
        MarkDto::class,
    )

    /** Substrings / FQNs that must never appear anywhere in a DTO's property type graph. */
    private val forbiddenSubstrings = listOf(
        "neo4j",
        "Cypher",
        "RetrievableIdentifier",
        "rag.model.Chunk",
        "com.embabel.agent.rag",
        "SimilarityResult",
        "TextSimilaritySearchRequest",
        // Any raw proposition-package type (e.g. the PropositionStatus enum) must be projected to a
        // primitive in a DTO, never exposed directly. DTOs surface enum names as Strings.
        "com.embabel.dice.proposition",
        "com.embabel.dice.provenance",
    )

    private val forbiddenExactFqns = listOf(
        "com.embabel.dice.proposition.Proposition",
        "com.embabel.dice.proposition.EntityMention",
        "com.embabel.dice.query.graph.GraphPath",
        "com.embabel.dice.query.graph.GraphNeighborhood",
        "com.embabel.dice.query.graph.RelatedEntity",
        "com.embabel.dice.query.graph.PropositionLineage",
        "com.embabel.dice.projection.lineage.ProjectionRecord",
        "com.embabel.dice.projection.memory.CollectorRunResult",
        "com.embabel.dice.spi.PropositionMark",
    )

    @Test
    fun `no discovery DTO exposes a store, RAG, graph, or domain type`() {
        val visited = mutableSetOf<KClass<*>>()
        val offenders = mutableListOf<String>()
        rootDtos.forEach { walk(it, visited, offenders, it.simpleName ?: "?") }
        assertTrue(
            offenders.isEmpty(),
            "discovery DTOs leak forbidden types:\n${offenders.joinToString("\n")}",
        )
    }

    @Test
    fun `every discovery DTO is reachable and was actually scanned`() {
        // Sanity: the walk visits at least the declared roots, so the gate is not vacuous.
        val visited = mutableSetOf<KClass<*>>()
        rootDtos.forEach { walk(it, visited, mutableListOf(), it.simpleName ?: "?") }
        assertTrue(visited.containsAll(rootDtos), "expected every root DTO to be scanned")
        assertEquals(5, RetrievalMode.entries.size, "RetrievalMode must expose exactly five modes")
    }

    @Test
    fun `discovery provenance has the exact primitive shape`() {
        assertEquals(
            mapOf(
                "locator" to "kotlin.String",
                "sourceRevision" to "kotlin.String?",
                "chunkId" to "kotlin.String?",
                "startOffset" to "kotlin.Int?",
                "endOffset" to "kotlin.Int?",
                "contentHash" to "kotlin.String?",
            ),
            DiscoveryProvenanceDto::class.memberProperties.associate {
                it.name to it.returnType.toString()
            },
        )
    }

    private fun walk(
        klass: KClass<*>,
        visited: MutableSet<KClass<*>>,
        offenders: MutableList<String>,
        path: String,
    ) {
        if (!visited.add(klass)) return
        klass.memberProperties.forEach { prop ->
            inspectType(prop.returnType, visited, offenders, "$path.${prop.name}")
        }
    }

    private fun inspectType(
        type: KType,
        visited: MutableSet<KClass<*>>,
        offenders: MutableList<String>,
        path: String,
    ) {
        val classifier = type.classifier as? KClass<*> ?: return
        val fqn = classifier.qualifiedName ?: classifier.java.name

        forbiddenSubstrings.forEach { needle ->
            if (fqn.contains(needle, ignoreCase = true)) {
                offenders.add("$path -> $fqn (matches forbidden substring '$needle')")
            }
        }
        if (fqn in forbiddenExactFqns) {
            offenders.add("$path -> $fqn (forbidden domain/graph/store type)")
        }

        // Recurse into the DTO graph for our own discovery types; descend into generic type
        // arguments (e.g. List<PropositionSummaryDto>) regardless.
        if (fqn.startsWith("com.embabel.dice.query.discovery.")) {
            walk(classifier, visited, offenders, path)
        }
        type.arguments.forEach { arg ->
            arg.type?.let { inspectType(it, visited, offenders, path) }
        }
    }
}
