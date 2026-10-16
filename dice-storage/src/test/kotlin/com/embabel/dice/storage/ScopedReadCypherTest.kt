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
import com.embabel.dice.proposition.ContextReadScope
import com.embabel.dice.proposition.EntityMention
import com.embabel.dice.proposition.Proposition
import com.embabel.dice.proposition.PropositionKey
import com.embabel.dice.proposition.PropositionQuery
import com.embabel.dice.proposition.PropositionStatus
import com.embabel.dice.proposition.ReadOrder
import com.embabel.dice.proposition.ScopedReadSpec
import com.embabel.dice.temporal.TemporalMetadata
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory
import org.junit.jupiter.api.TestInstance
import org.neo4j.driver.AuthTokens
import org.neo4j.driver.Driver
import org.neo4j.driver.GraphDatabase
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Executes the compiler in Neo4j; production read/hydration/timeout parity has a separate gate. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ScopedReadCypherTest {
    private lateinit var driver: Driver
    private val at = Instant.parse("2026-10-15T12:00:00.000000123Z")
    private val scope = ContextReadScope.ofValues(listOf("compiler-A", "compiler-B", "compiler-A"))

    @BeforeAll
    fun connect() {
        driver = GraphDatabase.driver(
            Neo4jTestContainer.instance.boltUrl,
            AuthTokens.basic("neo4j", Neo4jTestContainer.PASSWORD),
        )
    }

    @AfterAll
    fun disconnect() = driver.close()

    @AfterEach
    fun clean() {
        driver.session().use { it.run("MATCH (n:ScopedCompilerFixture) DETACH DELETE n").consume() }
    }

    private fun proposition(id: String = "one", context: String = "compiler-A"): Proposition = Proposition(
        id = id, contextId = ContextId(context), text = "fixture $id in $context",
        confidence = 0.9, decay = 0.1, importance = 0.6,
        created = at.minusSeconds(864000), contentRevised = at.minusSeconds(172800),
        metadataRevised = at.minusSeconds(259200), lastAccessed = at.minusSeconds(86400),
        level = 2, reinforceCount = 3, sourceIds = listOf("source-fixture"),
        mentions = listOf(EntityMention(span = "one", type = "fixture", resolvedId = "entity-1")),
    )

    private fun properties(p: Proposition): Map<String, Any?> {
        val n = PropositionGraphMapper.toView(p).proposition
        val props = linkedMapOf<String, Any?>(
            "id" to n.id, "contextId" to n.contextId, "text" to n.text,
            "confidence" to n.confidence, "decay" to n.decay, "importance" to n.importance,
            "created" to n.created.atOffset(ZoneOffset.UTC),
            "contentRevised" to n.contentRevised.atOffset(ZoneOffset.UTC),
            "metadataRevised" to n.metadataRevised.atOffset(ZoneOffset.UTC),
            "lastTouched" to n.lastTouched.atOffset(ZoneOffset.UTC),
            "lastAccessed" to n.lastAccessed.atOffset(ZoneOffset.UTC),
            "status" to n.status, "pinned" to n.pinned, "level" to n.level,
            "reinforceCount" to n.reinforceCount,
        )
        n.validFrom?.let { props["validFrom"] = it.atOffset(ZoneOffset.UTC) }
        n.validTo?.let { props["validTo"] = it.atOffset(ZoneOffset.UTC) }
        n.invalidatedAt?.let { props["invalidatedAt"] = it.atOffset(ZoneOffset.UTC) }
        n.metadata.forEach { (key, value) -> props["metadata.$key"] = value }
        return props
    }

    private fun save(p: Proposition) {
        driver.session().use { session ->
            session.run(
                "CREATE (p:ScopedCompilerProposition:ScopedCompilerFixture) SET p = $" + "props " +
                    "WITH p UNWIND $" + "mentions AS entity " +
                    "CREATE (p)-[:HAS_MENTION]->(:Mention:ScopedCompilerFixture {resolvedId: entity})",
                mapOf("props" to properties(p), "mentions" to p.mentions.mapNotNull { it.resolvedId }),
            ).consume()
        }
    }

    private fun spec(query: PropositionQuery? = null): ScopedReadSpec =
        ScopedReadSpec.at(at, 2.0).let { if (query == null) it else it.restrict(scope, query) }

    private fun ids(spec: ScopedReadSpec, order: ReadOrder = ReadOrder.NONE): List<String> {
        val compiled = ScopedReadCypher.compile(scope, spec)
        return driver.session().use { session ->
            session.run(
                "MATCH (p:ScopedCompilerProposition:ScopedCompilerFixture) WHERE ${compiled.predicate} " +
                    "RETURN p.id AS id ORDER BY ${ScopedReadCypher.order(order, spec)}",
                compiled.parameters,
            ).list { it["id"].asString() }
        }
    }

    @Test
    fun `scope composite inclusion and exclusion remain conjunctive`() {
        save(proposition("same"))
        save(proposition("same", "compiler-B"))
        save(proposition("same", "compiler-C"))
        assertEquals(listOf("same", "same"), ids(spec()))
        val included = spec().includingOnly(scope, listOf(PropositionKey("compiler-A", "same")))
        assertEquals(listOf("same"), ids(included))
        assertEquals(emptyList(), ids(included.excluding(scope, listOf(PropositionKey("compiler-A", "same")))))
        assertEquals(emptyList(), ids(spec().includingOnly(scope, emptyList())))
        assertEquals(emptyList(), ids(included.includingOnly(scope, listOf(PropositionKey("compiler-B", "same")))))
        assertEquals(listOf("same"), ids(spec().excluding(scope, listOf(PropositionKey("compiler-A", "same")))))
    }

    @Test
    fun `entities use correlated existence without duplicate roots`() {
        val p = proposition()
        save(p.copy(mentions = p.mentions + p.mentions + EntityMention("two", "fixture", "entity-2")))
        assertEquals(listOf("one"), ids(spec(PropositionQuery(entityId = "entity-1"))))
        assertEquals(listOf("one"), ids(spec(PropositionQuery(anyEntityIds = listOf("missing", "entity-2")))))
        assertEquals(listOf("one"), ids(spec(PropositionQuery(allEntityIds = listOf("entity-1", "entity-2", "entity-1")))))
        assertEquals(emptyList(), ids(spec(PropositionQuery(allEntityIds = listOf("entity-1", "missing")))))
        assertEquals(emptyList(), ids(spec(PropositionQuery(anyEntityIds = emptyList()))))
        assertEquals(listOf("one"), ids(spec(PropositionQuery(allEntityIds = emptyList()))))
        assertEquals(listOf("one"), ids(spec(PropositionQuery(statuses = emptySet()))))
    }

    @Test
    fun `every scalar membership field and inclusive endpoint is retained`() {
        val p = proposition()
        save(p)
        val boundaries = PropositionQuery(
            contextId = p.contextId, statuses = setOf(p.status), minLevel = 2, maxLevel = 2,
            createdAfter = p.created, createdBefore = p.created,
            revisedAfter = p.lastTouched, revisedBefore = p.lastTouched,
            accessedAfter = p.lastAccessed, accessedBefore = p.lastAccessed,
            minConfidence = p.confidence, minImportance = p.importance,
            minReinforceCount = 3, pinned = false,
            minEffectiveConfidence = 0.7, belowEffectiveConfidence = 0.8, decayK = 2.0,
            minTrustScore = 0.9,
        )
        assertEquals(listOf("one"), ids(spec(boundaries)))
        val rejecting = listOf(
            boundaries.copy(contextId = ContextId("compiler-B")),
            boundaries.copy(statuses = setOf(PropositionStatus.STALE)),
            boundaries.copy(minLevel = 3), boundaries.copy(maxLevel = 1),
            boundaries.copy(createdAfter = p.created.plusNanos(1)),
            boundaries.copy(createdBefore = p.created.minusNanos(1)),
            boundaries.copy(revisedAfter = p.lastTouched.plusNanos(1)),
            boundaries.copy(revisedBefore = p.lastTouched.minusNanos(1)),
            boundaries.copy(accessedAfter = p.lastAccessed.plusNanos(1)),
            boundaries.copy(accessedBefore = p.lastAccessed.minusNanos(1)),
            boundaries.copy(minConfidence = 0.91), boundaries.copy(minImportance = 0.61),
            boundaries.copy(minReinforceCount = 4), boundaries.copy(pinned = true),
            boundaries.copy(minEffectiveConfidence = 0.8), boundaries.copy(belowEffectiveConfidence = 0.7),
        )
        rejecting.forEachIndexed { index, query -> assertEquals(emptyList(), ids(spec(query)), "field $index") }
    }

    @Test
    fun `fragments cannot replace scope or earlier restrictions and each keeps its decay rate`() {
        save(proposition())
        val first = spec(PropositionQuery(entityId = "entity-1", minEffectiveConfidence = 0.8, decayK = 0.0))
        assertEquals(listOf("one"), ids(first))
        assertEquals(emptyList(), ids(first.restrict(scope, PropositionQuery(entityId = "missing"))))
        val combined = first.restrict(scope, PropositionQuery(belowEffectiveConfidence = 0.8, decayK = 2.0))
        assertEquals(listOf("one"), ids(combined))
        val compiled = ScopedReadCypher.compile(scope, combined)
        assertEquals(0.0, compiled.parameters["f0_decayK"])
        assertEquals(2.0, compiled.parameters["f1_decayK"])
    }

    @Test
    fun `trust only gates finite numeric metadata`() {
        val cases = listOf(null, "0.1", true, Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, 0.1, 0.8, 1)
        cases.forEachIndexed { index, score ->
            save(proposition("trust-$index").copy(metadata = if (score == null) emptyMap() else mapOf("dice.trust.score" to score)))
        }
        assertEquals(listOf("trust-0", "trust-1", "trust-2", "trust-3", "trust-4", "trust-5", "trust-7", "trust-8"),
            ids(spec(PropositionQuery(minTrustScore = 0.8))))
    }

    @Test
    fun `malicious values are bound and identifiers cannot be supplied`() {
        val attack = "x') MATCH (secret) DETACH DELETE secret //"
        val maliciousScope = ContextReadScope.ofValues(listOf(attack))
        val malicious = ScopedReadSpec.at(at, 2.0)
            .restrict(maliciousScope, PropositionQuery(contextId = ContextId(attack), entityId = attack,
                anyEntityIds = listOf(attack), allEntityIds = listOf(attack)))
            .includingOnly(maliciousScope, listOf(PropositionKey(attack, attack)))
            .excluding(maliciousScope, listOf(PropositionKey(attack, "denied")))
        val compiled = ScopedReadCypher.compile(maliciousScope, malicious)
        assertFalse(compiled.predicate.contains(attack))
        assertEquals(listOf(attack), compiled.parameters["scopeContexts"])
        assertEquals(attack, compiled.parameters["f0_entityId"])
        assertFailsWith<IllegalArgumentException> { ScopedConfidenceCypher.expression(attack, "asOf", "k") }
        assertFailsWith<IllegalArgumentException> { ScopedConfidenceCypher.expression("p", attack, "k") }
        save(proposition(id = attack, context = attack).copy(mentions = listOf(EntityMention(attack, "fixture", attack))))
        driver.session().use {
            assertEquals(1L, it.run("MATCH (p:ScopedCompilerProposition:ScopedCompilerFixture) WHERE ${compiled.predicate} RETURN count(p)",
                compiled.parameters).single()[0].asLong())
        }
    }

    @TestFactory
    fun `live confidence branches match Kotlin down to nanosecond day boundaries`(): List<DynamicTest> {
        val p = proposition()
        val fixtures = linkedMapOf(
            "decaying access anchor" to p,
            "newer content anchor" to p.copy(contentRevised = at.minusSeconds(43200)),
            "future anchor" to p.copy(lastAccessed = at.plusSeconds(86400)),
            "one nanosecond before day" to p.copy(lastAccessed = at.minusSeconds(86400).plusNanos(1)),
            "exact elapsed day" to p.copy(lastAccessed = at.minusSeconds(86400)),
            "one nanosecond after day" to p.copy(lastAccessed = at.minusSeconds(86400).minusNanos(1)),
            "validTo only ignored" to p.copy(temporal = TemporalMetadata(validTo = at.minusSeconds(1))),
            "invalidated now" to p.copy(temporal = TemporalMetadata(invalidatedAt = at)),
            "invalidated in future" to p.copy(temporal = TemporalMetadata(invalidatedAt = at.plusNanos(1))),
            "dated not yet valid" to p.copy(temporal = TemporalMetadata(validFrom = at.plusNanos(1))),
            "closed inside window" to p.copy(temporal = TemporalMetadata(validFrom = at.minusSeconds(864000), validTo = at.plusNanos(1))),
            "closed at end excluded" to p.copy(temporal = TemporalMetadata(validFrom = at.minusSeconds(864000), validTo = at)),
            "closed invalidated overrides" to p.copy(temporal = TemporalMetadata(validFrom = at.minusSeconds(864000), validTo = at.plusSeconds(1), invalidatedAt = at)),
            "open ended validFrom anchor" to p.copy(temporal = TemporalMetadata(validFrom = at.minusSeconds(432000))),
            "open ended starts now" to p.copy(temporal = TemporalMetadata(validFrom = at)),
        )
        return fixtures.flatMap { (name, value) -> listOf(0.0, 0.5, 2.0, 7.0).map { k ->
            DynamicTest.dynamicTest("$name k=$k") {
                val expression = ScopedConfidenceCypher.expression("p", "asOf", "k")
                val actual = driver.session().use {
                    it.run("WITH $" + "props AS p RETURN $expression AS confidence",
                        mapOf("props" to properties(value), "asOf" to at.toString(), "k" to k))
                        .single()["confidence"].asDouble()
                }
                assertEquals(value.effectiveConfidenceAt(at, k), actual, 1e-14)
            }
        } }
    }

    @Test
    fun `every order is deterministic and effective confidence is live`() {
        val old = proposition("old").copy(confidence = 1.0, decay = 0.8)
        val fresh = proposition("fresh").copy(confidence = 0.8, lastAccessed = at)
        save(old)
        save(fresh)
        ReadOrder.entries.forEach { order ->
            val result = ids(spec(), order)
            assertEquals(2, result.size)
            assertTrue(ScopedReadCypher.order(order, spec()).endsWith("p.contextId ASC, p.id ASC"))
        }
        assertEquals(listOf("fresh", "old"), ids(spec(), ReadOrder.EFFECTIVE_CONFIDENCE_DESC))
        val compiled = ScopedReadCypher.compile(scope, spec())
        assertEquals(at.toString(), compiled.parameters["rankAsOf"])
        assertEquals(2.0, compiled.parameters["rankDecayK"])
        assertFalse(ScopedReadCypher.order(ReadOrder.EFFECTIVE_CONFIDENCE_DESC, spec()).contains("p.effectiveConfidence"))
    }
}
