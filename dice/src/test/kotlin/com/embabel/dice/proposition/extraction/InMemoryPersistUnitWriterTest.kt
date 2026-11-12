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

import com.embabel.agent.core.ContextId
import com.embabel.agent.core.DataDictionary
import com.embabel.agent.rag.model.NamedEntityData
import com.embabel.agent.rag.model.SimpleNamedEntityData
import com.embabel.agent.rag.service.RelationshipData
import com.embabel.agent.rag.service.RetrievableIdentifier
import com.embabel.agent.rag.service.support.InMemoryNamedEntityDataRepository
import com.embabel.common.ai.model.EmbeddingService
import com.embabel.dice.proposition.EntityMention
import com.embabel.dice.proposition.MentionRole
import com.embabel.dice.proposition.Proposition
import com.embabel.dice.proposition.store.InMemoryPropositionRepository
import io.mockk.every
import io.mockk.mockk
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.Instant

class InMemoryPersistUnitWriterTest {

    private val tenant = ContextId("mem-tenant")

    private val schema = DataDictionary.fromClasses("mem")

    private val runs = InMemoryExtractionRunStore()

    private val missingRun = ExtractionRunKey.of("mem-tenant", "never-saved")

    /**
     * Saves a running run to the run store.
     *
     * @param id The run id.
     * @return The run's key.
     */
    private fun run(id: String): ExtractionRunKey {
        runs.save(
            ExtractionRun(
                contextId = tenant,
                lineage = ExtractionRunLineage.root(ExtractionRunRef(id)),
                status = ExtractionRunStatus.RUNNING,
                startedAt = Instant.parse("2026-08-31T10:15:30Z"),
            ),
        )
        return ExtractionRunKey.of("mem-tenant", id)
    }

    /**
     * A person entity.
     *
     * @param id The entity id, also used as its name.
     * @param description The entity description.
     * @return The entity.
     */
    private fun entity(id: String, description: String = "test") = SimpleNamedEntityData(
        id = id,
        name = id,
        description = description,
        labels = setOf("Person"),
        properties = emptyMap(),
    )

    /**
     * A proposition in the test tenant that mentions one entity.
     *
     * @param text The proposition text.
     * @param id The proposition id.
     * @param entityId The entity its mention resolves to.
     * @return The proposition.
     */
    private fun proposition(text: String, id: String, entityId: String = "entity-alice") = Proposition(
        id = id,
        contextId = tenant,
        text = text,
        mentions = listOf(
            EntityMention(span = "Alice", type = "Person", resolvedId = entityId, role = MentionRole.SUBJECT),
        ),
        confidence = 0.9,
        grounding = listOf("chunk-1"),
    )

    /**
     * A unit with one new claim, p-1, and the new entity it mentions.
     *
     * @return The unit, with no run.
     */
    private fun aliceUnit() =
        PersistUnit.of(listOf(proposition("Alice likes tea", "p-1")), listOf(entity("entity-alice")), emptyList())

    /**
     * An in-memory entity repository that records its write calls and throws where a test asks it to.
     *
     * @param failingSave The number of the save call that throws, counting from one, or zero for none.
     * @param findFault Thrown by every `findById`, when set.
     * @param deleteFault Thrown by every `delete`, when set.
     * @param relationshipFault Thrown by every `createRelationship`, when set.
     */
    private class FaultyEntityRepository(
        schema: DataDictionary,
        private val failingSave: Int = 0,
        private val findFault: Exception? = null,
        private val deleteFault: Exception? = null,
        private val relationshipFault: Throwable? = null,
    ) : InMemoryNamedEntityDataRepository(schema) {

        /** Every write call, in order. */
        val writes = mutableListOf<String>()

        override fun save(entity: NamedEntityData): NamedEntityData {
            writes += "save ${entity.id}"
            if (writes.count { it.startsWith("save ") } == failingSave) {
                throw IllegalStateException("entity store down")
            }
            return super.save(entity)
        }

        override fun findById(id: String): NamedEntityData? {
            findFault?.let { throw it }
            return super.findById(id)
        }

        override fun delete(id: String): Boolean {
            writes += "delete $id"
            deleteFault?.let { throw it }
            return super.delete(id)
        }

        override fun createRelationship(
            a: RetrievableIdentifier,
            b: RetrievableIdentifier,
            relationship: RelationshipData,
        ) {
            writes += "relationship ${relationship.name}"
            relationshipFault?.let { throw it }
            super.createRelationship(a, b, relationship)
        }
    }

    /**
     * One set of stores and a writer over them.
     *
     * @param entities The entity repository the writer uses.
     * @param embeddingService The proposition repository's embedder, or null for none.
     */
    private inner class Stores(
        val entities: FaultyEntityRepository = FaultyEntityRepository(schema),
        embeddingService: EmbeddingService? = null,
    ) {
        val claims = InMemoryPropositionRepository(embeddingService)

        val links = InMemoryPropositionRunLinkStore(runs, claims)

        val writer = PersistUnitWriter.inMemory(claims, entities, links)

        /**
         * What the stores hold for every id the unit touches.
         *
         * @param unit The unit whose ids to read.
         * @return The claims, entities and run links for those ids.
         */
        fun picture(unit: PersistUnit) = Picture(
            claims = unit.propositions.map { claims.findById(it.id) },
            entities = (unit.newEntities + unit.updatedEntities).map { entities.findById(it.id) },
            runLinks = unit.run?.let { links.propositionsOf(it, 100) } ?: emptyList(),
        )
    }

    /**
     * What the stores held for a unit's ids at one moment.
     *
     * @property claims The proposition for each of the unit's proposition ids, or null when absent
     * @property entities The entity for each of the unit's entity ids, or null when absent
     * @property runLinks The ids linked to the unit's run
     */
    private class Picture(
        val claims: List<Proposition?>,
        val entities: List<NamedEntityData?>,
        val runLinks: List<String>,
    )

    /**
     * Asserts that every claim and entity is the same instance as before, and the run links are equal.
     *
     * @param before What the stores held before the write.
     * @param after What the stores hold after it.
     */
    private fun assertUnchanged(before: Picture, after: Picture) {
        before.claims.zip(after.claims).forEach { (was, now) -> assertThat(now).isSameAs(was) }
        before.entities.zip(after.entities).forEach { (was, now) -> assertThat(now).isSameAs(was) }
        assertThat(after.runLinks).isEqualTo(before.runLinks)
    }

    /**
     * Asserts the outcome is a failure.
     *
     * @param outcome What the writer returned.
     * @return The outcome as a [PersistOutcome.Failed].
     */
    private fun failed(outcome: PersistOutcome): PersistOutcome.Failed {
        assertThat(outcome).isInstanceOf(PersistOutcome.Failed::class.java)
        return outcome as PersistOutcome.Failed
    }

    @Test
    fun `E1 a successful write stores claims, entities and the run link and returns Stored`() {
        val stores = Stores()
        val key = run("run-1")
        val p1 = proposition("Alice likes tea", "p-1")
        val p2 = proposition("Bob owns a cat", "p-2", entityId = "entity-bob")
        val alice = entity("entity-alice")
        val bob = entity("entity-bob")
        val unit = PersistUnit.of(listOf(p1, p2), listOf(alice, bob), emptyList()).withRun(key)

        val outcome = stores.writer.write(unit)

        assertThat(outcome).isInstanceOf(PersistOutcome.Stored::class.java)
        assertThat(stores.claims.findById("p-1")).isSameAs(p1)
        assertThat(stores.claims.findById("p-2")).isSameAs(p2)
        assertThat(stores.entities.findById("entity-alice")).isSameAs(alice)
        assertThat(stores.entities.findById("entity-bob")).isSameAs(bob)
        assertThat(stores.links.propositionsOf(key, 100)).containsExactly("p-1", "p-2")
    }

    @Test
    fun `E2 a failing second entity save leaves the stores unchanged and removes the first entity`() {
        val stores = Stores(FaultyEntityRepository(schema, failingSave = 2))
        val unit = PersistUnit.of(
            listOf(
                proposition("Alice likes tea", "p-1"),
                proposition("Bob owns a cat", "p-2", entityId = "entity-bob"),
            ),
            listOf(entity("entity-alice"), entity("entity-bob")),
            emptyList(),
        ).withRun(run("run-1"))
        val before = stores.picture(unit)

        val failed = failed(stores.writer.write(unit))

        assertThat(failed.step).isEqualTo(PersistStep.ENTITIES)
        assertThat(failed.leftInStore).isEmpty()
        assertThat(stores.entities.writes).contains("save entity-alice", "delete entity-alice")
        assertThat(stores.entities.findById("entity-alice")).isNull()
        assertUnchanged(before, stores.picture(unit))
    }

    @Test
    fun `E3 a failing embedding on the second proposition leaves the stores unchanged including the first`() {
        val embedder = mockk<EmbeddingService>()
        every { embedder.embed("boom") } throws IllegalStateException("embedder down")
        every { embedder.embed(neq("boom")) } returns floatArrayOf(1f, 0f)
        val stores = Stores(embeddingService = embedder)
        val unit = PersistUnit.of(
            listOf(proposition("Alice likes tea", "p-1"), proposition("boom", "p-2")),
            listOf(entity("entity-alice")),
            emptyList(),
        ).withRun(run("run-1"))
        val before = stores.picture(unit)

        val failed = failed(stores.writer.write(unit))

        assertThat(failed.step).isEqualTo(PersistStep.PROPOSITIONS)
        assertThat(failed.leftInStore).isEmpty()
        assertThat(stores.claims.findById("p-1")).isNull()
        assertUnchanged(before, stores.picture(unit))
    }

    @Test
    fun `E4 a STRICT run link failure leaves the stores unchanged and reports the lineage failure`() {
        val stores = Stores()
        val unit = aliceUnit().withRun(missingRun)
        val before = stores.picture(unit)

        val failed = failed(stores.writer.write(unit))

        assertThat(failed.step).isEqualTo(PersistStep.RUN_LINKS)
        assertThat(failed.leftInStore).isEmpty()
        assertThat(failed.cause).isInstanceOf(LineageNotRecordedException::class.java)
        assertUnchanged(before, stores.picture(unit))
    }

    @Test
    fun `E5 a failing relationship leaves claims, entities and run links unchanged and reports the relationships`() {
        val stores = Stores(FaultyEntityRepository(schema, relationshipFault = IllegalStateException("graph down")))
        val unit = aliceUnit().withRun(run("run-1"))
        val before = stores.picture(unit)

        val failed = failed(stores.writer.write(unit))

        assertThat(failed.step).isEqualTo(PersistStep.STRUCTURAL_RELATIONSHIPS)
        assertThat(failed.leftInStore).containsExactly(PersistStep.STRUCTURAL_RELATIONSHIPS)
        assertUnchanged(before, stores.picture(unit))
    }

    @Test
    fun `E6 a failed unit puts back the claim and entity it updated and deletes neither`() {
        val stores = Stores()
        val oldClaim = proposition("Alice likes coffee", "p-1")
        val oldAlice = entity("entity-alice", description = "before")
        stores.claims.save(oldClaim)
        stores.entities.save(oldAlice)
        val unit = PersistUnit.of(
            listOf(proposition("Alice likes tea", "p-1")),
            emptyList(),
            listOf(entity("entity-alice", description = "after")),
        ).withRun(missingRun)

        val failed = failed(stores.writer.write(unit))

        assertThat(failed.step).isEqualTo(PersistStep.RUN_LINKS)
        assertThat(stores.claims.findById("p-1")).isSameAs(oldClaim)
        assertThat(stores.entities.findById("entity-alice")).isSameAs(oldAlice)
    }

    @Test
    fun `E7 a run link that existed before the unit survives a unit that fails at the relationships`() {
        val stores = Stores(FaultyEntityRepository(schema, relationshipFault = IllegalStateException("graph down")))
        val key = run("run-1")
        stores.claims.save(proposition("Alice was here first", "p0"))
        stores.links.link(key, listOf("p0"))
        // p-1 exists before the unit, so after the claims are restored only the unlink removes its new link.
        val oldClaim = proposition("Alice likes coffee", "p-1")
        stores.claims.save(oldClaim)
        val unit = aliceUnit().withRun(key)

        val failed = failed(stores.writer.write(unit))

        assertThat(failed.step).isEqualTo(PersistStep.STRUCTURAL_RELATIONSHIPS)
        assertThat(stores.claims.findById("p-1")).isSameAs(oldClaim)
        assertThat(stores.links.propositionsOf(key, 100)).containsExactly("p0")
    }

    @Test
    fun `E8 a LENIENT run link failure returns Stored and undoes nothing`() {
        val stores = Stores()
        val p1 = proposition("Alice likes tea", "p-1")
        val alice = entity("entity-alice")
        val unit = PersistUnit.of(listOf(p1), listOf(alice), emptyList())
            .withRun(missingRun, LineageFailurePolicy.LENIENT)

        val outcome = stores.writer.write(unit)

        assertThat(outcome).isInstanceOf(PersistOutcome.Stored::class.java)
        assertThat(outcome.lineageFailure).isNotNull()
        assertThat(stores.claims.findById("p-1")).isSameAs(p1)
        assertThat(stores.entities.findById("entity-alice")).isSameAs(alice)
        assertThat(stores.entities.writes).doesNotContain("delete entity-alice")
    }

    @Test
    fun `E9 a failure while recording before-images fails at PREPARE and writes nothing`() {
        val stores = Stores(FaultyEntityRepository(schema, findFault = IllegalStateException("entity store down")))
        val key = run("run-1")
        val unit = aliceUnit().withRun(key)

        val failed = failed(stores.writer.write(unit))

        assertThat(failed.step).isEqualTo(PersistStep.PREPARE)
        assertThat(failed.leftInStore).isEmpty()
        assertThat(stores.entities.writes).isEmpty()
        assertThat(stores.claims.count()).isZero()
        assertThat(stores.links.linkedTo(key)).isEmpty()
    }

    @Test
    fun `E10 an Error in the relationship step propagates and the stores are unchanged`() {
        val stores = Stores(FaultyEntityRepository(schema, relationshipFault = NotImplementedError("no edges here")))
        val unit = aliceUnit().withRun(run("run-1"))
        val before = stores.picture(unit)

        assertThatThrownBy { stores.writer.write(unit) }.isInstanceOf(NotImplementedError::class.java)

        assertUnchanged(before, stores.picture(unit))
    }

    @Test
    fun `E11 an undo that throws leaves ENTITIES in leftInStore and its exception in the suppressed list`() {
        val deleteFault = IllegalStateException("cannot delete")
        val stores = Stores(FaultyEntityRepository(schema, deleteFault = deleteFault))
        val unit = aliceUnit().withRun(missingRun)

        val failed = failed(stores.writer.write(unit))

        assertThat(failed.step).isEqualTo(PersistStep.RUN_LINKS)
        assertThat(failed.leftInStore).containsExactly(PersistStep.ENTITIES)
        assertThat(failed.cause.suppressed).containsExactly(deleteFault)
    }

    @Test
    fun `E12 a failed unit removes the run links it added and keeps the links the run already had`() {
        val stores = Stores(FaultyEntityRepository(schema, relationshipFault = IllegalStateException("graph down")))
        val key = run("run-1")
        val oldTea = proposition("Alice likes coffee", "p-1")
        val oldCat = proposition("Alice owns a dog", "p-2")
        stores.claims.save(oldTea)
        stores.claims.save(oldCat)
        stores.links.link(key, listOf("p-2"))
        val unit = PersistUnit.of(
            listOf(proposition("Alice likes tea", "p-1"), proposition("Alice owns a cat", "p-2")),
            listOf(entity("entity-alice")),
            emptyList(),
        ).withRun(key)

        val failed = failed(stores.writer.write(unit))

        assertThat(failed.step).isEqualTo(PersistStep.STRUCTURAL_RELATIONSHIPS)
        assertThat(stores.claims.findById("p-1")).isSameAs(oldTea)
        assertThat(stores.claims.findById("p-2")).isSameAs(oldCat)
        assertThat(stores.links.linkedTo(key)).containsExactly("p-2")
    }
}
