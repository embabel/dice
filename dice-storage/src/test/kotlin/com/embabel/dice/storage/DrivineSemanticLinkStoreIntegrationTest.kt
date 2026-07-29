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
import com.embabel.dice.report.LinkKind
import com.embabel.dice.report.ReviewStatus
import com.embabel.dice.report.SemanticLink
import org.drivine.manager.PersistenceManager
import org.drivine.query.QuerySpecification
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean

/**
 * Integration tests for [DrivineSemanticLinkStore] against the module's real Neo4j
 * testcontainer harness.
 */
@SpringBootTest(classes = [TestApplication::class, SemanticLinkStoreTestConfiguration::class])
internal class DrivineSemanticLinkStoreIntegrationTest {

    @Autowired
    private lateinit var linkStore: DrivineSemanticLinkStore

    @Autowired
    private lateinit var persistenceManager: PersistenceManager

    @BeforeEach
    fun createSchema() {
        persistenceManager.execute(
            QuerySpecification.withStatement(
                """
                CREATE CONSTRAINT semantic_link_context_id_unique IF NOT EXISTS
                FOR (l:SemanticLink) REQUIRE (l.contextId, l.id) IS UNIQUE
                """.trimIndent(),
            ),
        )
        persistenceManager.execute(
            QuerySpecification.withStatement(
                """
                CREATE INDEX semantic_link_context_id IF NOT EXISTS
                FOR (l:SemanticLink) ON (l.contextId)
                """.trimIndent(),
            ),
        )
    }

    @AfterEach
    fun cleanUp() {
        persistenceManager.execute(
            QuerySpecification.withStatement("MATCH (l:SemanticLink) DETACH DELETE l"),
        )
    }

    @Test
    fun `record and find round-trip every scalar and string-list property`() {
        val contextId = ContextId("ctx-round-trip")
        val link = semanticLink(
            sourceEntityId = "entity-a",
            targetEntityId = "entity-b",
            connectingEntityIds = listOf("bridge-1", "bridge-2"),
            sourcePropositionIds = listOf("prop-1", "prop-2"),
            confidence = 0.82,
            rationale = "A and B share two supporting paths",
        )

        assertEquals(link, linkStore.record(contextId, link))
        assertEquals(listOf(link), linkStore.find(contextId))
        assertEquals(listOf(link), linkStore.find(contextId, ReviewStatus.CANDIDATE))
        assertEquals(emptyList<SemanticLink>(), linkStore.find(contextId, ReviewStatus.ACCEPTED))
    }

    @Test
    fun `rediscovery preserves rejected status while refreshing evidence`() {
        val contextId = ContextId("ctx-sticky")
        val original = semanticLink(rationale = "first rationale", confidence = 0.55)
        val id = linkStore.idOf(original)

        linkStore.record(contextId, original)
        val rejected = linkStore.updateReviewStatus(contextId, id, ReviewStatus.REJECTED)
        assertEquals(ReviewStatus.REJECTED, rejected?.reviewStatus)

        val rediscovered = original.copy(
            connectingEntityIds = listOf("new-bridge"),
            sourcePropositionIds = listOf("new-proposition"),
            confidence = 0.91,
            rationale = "refreshed rationale",
        )
        val stored = linkStore.record(contextId, rediscovered)

        assertEquals(ReviewStatus.REJECTED, stored.reviewStatus)
        assertEquals(listOf("new-bridge"), stored.connectingEntityIds)
        assertEquals(listOf("new-proposition"), stored.sourcePropositionIds)
        assertEquals(0.91, stored.confidence)
        assertEquals("refreshed rationale", stored.rationale)
    }

    @Test
    fun `reversed endpoints merge to the same natural id`() {
        val contextId = ContextId("ctx-reversed")
        val forward = semanticLink(sourceEntityId = "alpha", targetEntityId = "omega")
        val reversed = semanticLink(sourceEntityId = "omega", targetEntityId = "alpha")

        assertEquals(linkStore.idOf(forward), linkStore.idOf(reversed))
        linkStore.record(contextId, forward)
        linkStore.record(contextId, reversed)

        assertEquals(listOf(reversed), linkStore.find(contextId))
    }

    @Test
    fun `injection-shaped rationale round-trips as data without affecting other nodes`() {
        val contextId = ContextId("ctx-injection")
        val injectionShapedRationale = "'} MATCH (n) DETACH DELETE n //"
        val adversarial = semanticLink(
            sourceEntityId = "adversarial-a",
            targetEntityId = "adversarial-b",
            rationale = injectionShapedRationale,
        )
        val sentinel = semanticLink(
            sourceEntityId = "sentinel-a",
            targetEntityId = "sentinel-b",
            rationale = "must survive",
        )

        linkStore.record(contextId, sentinel)
        linkStore.record(contextId, adversarial)

        val links = linkStore.find(contextId)
        assertEquals(2, links.size)
        assertEquals(injectionShapedRationale, links.single { it.sourceEntityId == "adversarial-a" }.rationale)
        assertEquals("must survive", links.single { it.sourceEntityId == "sentinel-a" }.rationale)
    }

    @Test
    fun `the same natural link and its review lifecycle remain independent across contexts`() {
        val contextA = ContextId("ctx-a")
        val contextB = ContextId("ctx-b")
        val original = semanticLink(
            sourceEntityId = "shared-a",
            targetEntityId = "shared-b",
        )
        val id = linkStore.idOf(original)

        linkStore.record(contextA, original.copy(rationale = "context A initial"))
        linkStore.record(contextB, original.copy(rationale = "context B initial"))
        linkStore.updateReviewStatus(contextA, id, ReviewStatus.REJECTED)
        linkStore.updateReviewStatus(contextB, id, ReviewStatus.ACCEPTED)

        val rediscoveredA = original.copy(
            sourcePropositionIds = listOf("prop-a-refreshed"),
            confidence = 0.81,
            rationale = "context A refreshed",
        )
        val rediscoveredB = original.copy(
            sourcePropositionIds = listOf("prop-b-refreshed"),
            confidence = 0.92,
            rationale = "context B refreshed",
        )
        val storedA = linkStore.record(contextA, rediscoveredA)
        val storedB = linkStore.record(contextB, rediscoveredB)

        assertEquals(ReviewStatus.REJECTED, storedA.reviewStatus)
        assertEquals(ReviewStatus.ACCEPTED, storedB.reviewStatus)
        assertEquals(
            listOf(rediscoveredA.copy(reviewStatus = ReviewStatus.REJECTED)),
            linkStore.find(contextA),
        )
        assertEquals(
            listOf(rediscoveredB.copy(reviewStatus = ReviewStatus.ACCEPTED)),
            linkStore.find(contextB),
        )
    }

    @Test
    fun `find warns and skips a corrupt row without hiding readable links`() {
        val contextId = ContextId("ctx-corrupt")
        val valid = semanticLink(sourceEntityId = "valid-a", targetEntityId = "valid-b")
        linkStore.record(contextId, valid)
        persistenceManager.execute(
            QuerySpecification.withStatement(
                """
                CREATE (:SemanticLink {
                    id: ${'$'}id,
                    contextId: ${'$'}contextId,
                    sourceEntityId: ${'$'}sourceEntityId,
                    targetEntityId: ${'$'}targetEntityId,
                    kind: ${'$'}kind,
                    reviewStatus: ${'$'}reviewStatus,
                    confidence: ${'$'}confidence,
                    rationale: ${'$'}rationale,
                    connectingEntityIds: ${'$'}connectingEntityIds,
                    sourcePropositionIds: ${'$'}sourcePropositionIds,
                    createdAt: ${'$'}now
                })
                """.trimIndent(),
            ).bind(
                mapOf(
                    "id" to "corrupt|row|INFERRED",
                    "contextId" to contextId.value,
                    "sourceEntityId" to "corrupt",
                    "targetEntityId" to "row",
                    "kind" to "NOT_A_LINK_KIND",
                    "reviewStatus" to ReviewStatus.CANDIDATE.name,
                    "confidence" to 0.5,
                    "rationale" to "bad enum",
                    "connectingEntityIds" to emptyList<String>(),
                    "sourcePropositionIds" to listOf("prop-corrupt"),
                    "now" to "2026-07-28T00:00:00Z",
                ),
            ),
        )

        assertEquals(listOf(valid), linkStore.find(contextId))
    }

    private fun semanticLink(
        sourceEntityId: String = "source",
        targetEntityId: String = "target",
        connectingEntityIds: List<String> = listOf("bridge"),
        sourcePropositionIds: List<String> = listOf("prop"),
        confidence: Double = 0.75,
        rationale: String? = "rationale",
    ) = SemanticLink(
        sourceEntityId = sourceEntityId,
        targetEntityId = targetEntityId,
        connectingEntityIds = connectingEntityIds,
        kind = LinkKind.INFERRED,
        sourcePropositionIds = sourcePropositionIds,
        confidence = confidence,
        rationale = rationale,
    )
}

@TestConfiguration(proxyBeanMethods = false)
internal class SemanticLinkStoreTestConfiguration {

    @Bean
    fun semanticLinkStore(persistenceManager: PersistenceManager): DrivineSemanticLinkStore =
        DrivineSemanticLinkStore(persistenceManager)
}
