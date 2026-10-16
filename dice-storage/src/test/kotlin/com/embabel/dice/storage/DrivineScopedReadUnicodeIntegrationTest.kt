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
import com.embabel.dice.proposition.Proposition
import com.embabel.dice.proposition.PropositionKey
import com.embabel.dice.proposition.ReadOrder
import com.embabel.dice.proposition.ScopedReadSpec
import com.embabel.dice.proposition.store.InMemoryPropositionRepository
import org.drivine.connection.DataSourceMap
import org.drivine.connection.DatabaseRegistry
import org.drivine.manager.PersistenceManager
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import java.time.Instant
import kotlin.test.assertEquals

@SpringBootTest(classes = [TestApplication::class])
class DrivineScopedReadUnicodeIntegrationTest {
    companion object {
        @JvmStatic
        @DynamicPropertySource
        fun neo4jProperties(registry: DynamicPropertyRegistry) = Neo4jTestContainer.registerProperties(registry)
    }

    @Autowired private lateinit var repository: DrivinePropositionRepository
    @Autowired private lateinit var manager: PersistenceManager
    @Autowired private lateinit var registry: DatabaseRegistry
    @Autowired private lateinit var sources: DataSourceMap

    private val at = Instant.parse("2026-10-15T12:00:00.000000123Z")
    private val controls = listOf("z", "a", "aa", "a😀", "a\uE000", "a😀a", "𐀀", "😀", "\uE000")
    private val limits = listOf(1, 2, 5, 100)
    private val tokens = listOf(" ALPHA ", "beta", "alpha", "", "  ")
    private fun native() = repository.withNativeReadExecutor(Neo4jScopedReadExecutor.from(manager, registry, sources))
    private fun key(proposition: Proposition) = PropositionKey(proposition.contextId.value, proposition.id)
    private fun proposition(id: String, context: String) = Proposition(
        id = id, text = "alpha beta $id $context", contextId = ContextId(context), confidence = 0.9,
        importance = 0.6, reinforceCount = 3, created = at, contentRevised = at, metadataRevised = at,
        lastAccessed = at, mentions = emptyList(),
    )

    @AfterEach
    fun clean() { repository.clearAll() }

    @TestFactory
    fun contextUnicodeTies(): List<DynamicTest> = parityCases(
        listOf("\uE000", "😀").mapIndexed { index, context -> proposition("context-$index", "unicode-$context") },
    )

    @TestFactory
    fun sameContextIdUnicodeTies(): List<DynamicTest> = parityCases(
        listOf("\uE000", "😀").map { proposition("id-$it", "unicode-ids") },
    )

    @TestFactory
    fun contextAsciiAndPrefixControls(): List<DynamicTest> = parityCases(
        controls.mapIndexed { index, context -> proposition("context-$index", "unicode-$context") },
    )

    @TestFactory
    fun sameContextIdAsciiAndPrefixControls(): List<DynamicTest> = parityCases(
        controls.map { proposition("id-$it", "unicode-ids") },
    )

    private fun parityCases(fixtures: List<Proposition>): List<DynamicTest> {
        val local by lazy {
            clean()
            InMemoryPropositionRepository().also { resident ->
                fixtures.forEach { repository.save(it); resident.save(it) }
            }
        }
        val scope = ContextReadScope.ofValues(fixtures.map { it.contextId.value })
        val spec = ScopedReadSpec.at(at, 2.0)
        val expected = fixtures.sortedWith(compareBy<Proposition> { it.contextId.value }.thenBy { it.id }).map(::key)
        val queryCases = ReadOrder.entries.flatMap { order -> limits.map { limit ->
            DynamicTest.dynamicTest("query order=$order limit=$limit") {
                val resident = local.query(scope, spec, order, limit).map(::key)
                assertEquals(expected.take(limit), resident, "resident tied primary")
                assertEquals(resident, native().query(scope, spec, order, limit).map(::key))
            }
        } }
        val keywordCases = limits.map { limit ->
            DynamicTest.dynamicTest("positive keyword overlap limit=$limit") {
                val resident = local.keywordOverlap(scope, spec, tokens, limit).map(::key)
                assertEquals(expected.take(limit), resident, "resident positive overlap and effective-confidence ties")
                assertEquals(resident, native().keywordOverlap(scope, spec, tokens, limit).map(::key))
            }
        }
        return queryCases + keywordCases
    }
}
