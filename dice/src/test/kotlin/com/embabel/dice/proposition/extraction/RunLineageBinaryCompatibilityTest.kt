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

import com.embabel.agent.core.DataDictionary
import com.embabel.agent.rag.model.NamedEntity
import com.embabel.agent.rag.service.NamedEntityDataRepository
import com.embabel.dice.common.EntityResolver
import com.embabel.dice.common.Relations
import com.embabel.dice.incremental.ChunkHistoryStore
import com.embabel.dice.pipeline.PropositionPipeline
import com.embabel.dice.projection.graph.GraphProjectionService
import com.embabel.dice.projection.grounding.GroundingWiringService
import com.embabel.dice.proposition.PropositionRepository
import kotlin.jvm.functions.Function1
import kotlin.jvm.functions.Function2
import com.embabel.dice.proposition.store.InMemoryPropositionRepository
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.function.Function

/**
 * The constructor ABI of [IncrementalPropositionExtraction], which run lineage must not move.
 *
 * **Why this test exists.** Kotlin compiles a constructor with default arguments into one synthetic
 * `<init>(...every parameter..., int mask, DefaultConstructorMarker)`, and *that* is the descriptor a
 * precompiled Kotlin caller links against whenever it omits any argument. Appending a defaulted
 * parameter — even a trailing one, even with `@JvmOverloads` faithfully republishing every Java
 * overload — rewrites it, and every such caller fails with `NoSuchMethodError` at runtime. Java
 * overload preservation does not cover this, because Java callers never touch the synthetic
 * constructor and Kotlin callers using defaults never touch anything else.
 *
 * So the run link store is not a constructor parameter. It is bound by
 * [IncrementalPropositionExtraction.withRunLineage] after construction, which adds a method instead
 * of moving a descriptor.
 *
 * `SourceAnalysisContext` took the opposite trade in the profiles slice and said so: it is a data
 * class whose `copy`/`componentN` ABI was explicitly not claimed. This class is not a data class and
 * has no such carve-out, so the descriptor has to hold.
 */
class RunLineageBinaryCompatibilityTest {

    /** The 16 parameter types the primary constructor had before run lineage, in order. */
    private val slice8Parameters: List<Class<*>> = listOf(
        PropositionPipeline::class.java,
        ChunkHistoryStore::class.java,
        DataDictionary::class.java,
        Relations::class.java,
        PropositionRepository::class.java,
        NamedEntityDataRepository::class.java,
        EntityResolver::class.java,
        GraphProjectionService::class.java,
        PropositionExtractionProperties::class.java,
        Function::class.java,
        Function::class.java,
        Function2::class.java,
        GroundingWiringService::class.java,
        Function1::class.java,
        Boolean::class.javaPrimitiveType!!,
        Function1::class.java,
    )

    private val marker: Class<*> = Class.forName("kotlin.jvm.internal.DefaultConstructorMarker")

    @Test
    fun `the synthetic default-argument constructor still has its slice 8 descriptor`() {
        // The one a precompiled Kotlin caller links against when it omits any argument. If run
        // lineage had been appended to the primary constructor this would carry a
        // PropositionRunLinkStore before the mask, and every such caller would get NoSuchMethodError.
        val synthetic = IncrementalPropositionExtraction::class.java.declaredConstructors
            .map { it.parameterTypes.toList() }
            .filter { it.size >= 2 && it[it.size - 1] == marker }

        assertTrue(
            slice8Parameters + listOf(Int::class.javaPrimitiveType!!, marker) in synthetic,
            "the synthetic default-argument constructor moved; a Kotlin caller compiled against " +
                "slice 8 and using any constructor default would now fail with NoSuchMethodError. " +
                "Published synthetics: $synthetic",
        )
    }

    @Test
    fun `every Java overload descriptor that existed before run lineage survives`() {
        val published = IncrementalPropositionExtraction::class.java.constructors
            .map { it.parameterTypes.toList() }
            .toSet()

        // @JvmOverloads publishes one constructor per defaulted-parameter prefix. Nine required
        // parameters, then one more overload for each of the seven defaults.
        for (arity in 9..16) {
            assertTrue(
                slice8Parameters.take(arity) in published,
                "constructor of $arity arguments no longer published",
            )
        }
    }

    @Test
    fun `no published constructor mentions the run link store`() {
        // The positive statement of the rule: run lineage is bound by a method, so it appears in no
        // constructor descriptor at all — synthetic or published.
        val everyConstructorParameter = IncrementalPropositionExtraction::class.java.declaredConstructors
            .flatMap { it.parameterTypes.toList() }

        assertTrue(
            everyConstructorParameter.none { it == PropositionRunLinkStore::class.java },
            "PropositionRunLinkStore reached a constructor descriptor; bind it with withRunLineage",
        )
    }

    @Test
    fun `run lineage binds once and a second attempt is rejected`() {
        // The field is read when an analysis records lineage, not when it starts, so a later
        // rebinding would redirect or silently erase the audit record of an extraction already in
        // flight. An audit surface should not be swappable at a distance; a second call is a
        // programming error and says so. Clearing it with null is the same call and the same answer.
        val extraction = extraction()
        val first = InMemoryPropositionRunLinkStore(InMemoryExtractionRunStore(), InMemoryPropositionRepository())
        val second = InMemoryPropositionRunLinkStore(InMemoryExtractionRunStore(), InMemoryPropositionRepository())

        assertSame(extraction, extraction.withRunLineage(first))

        assertThrows(IllegalStateException::class.java) { extraction.withRunLineage(second) }
        assertThrows(IllegalStateException::class.java) { extraction.withRunLineage(null) }
    }

    @Test
    fun `binding no lineage is still a binding`() {
        // `withRunLineage(null)` is how a host says "record none" explicitly. It is one binding, so
        // it cannot later be upgraded into one that records — otherwise "bound once" would depend
        // on which value was passed.
        val extraction = extraction()
        extraction.withRunLineage(null)

        assertThrows(IllegalStateException::class.java) {
            extraction.withRunLineage(
                InMemoryPropositionRunLinkStore(InMemoryExtractionRunStore(), InMemoryPropositionRepository()),
            )
        }
    }

    private fun extraction() = IncrementalPropositionExtraction(
        propositionPipeline = mockk(relaxed = true),
        chunkHistoryStore = mockk(relaxed = true),
        dataDictionary = DataDictionary.fromClasses("binding"),
        relations = Relations.empty(),
        propositionRepository = mockk(relaxed = true),
        entityRepository = mockk(relaxed = true),
        entityResolver = mockk(relaxed = true),
        graphProjectionService = mockk(relaxed = true),
        properties = PropositionExtractionProperties(),
    )

    @Test
    fun `withRunLineage is the binding point and returns the same instance`() {
        val extraction = IncrementalPropositionExtraction::class.java.methods
            .filter { it.name == "withRunLineage" }

        assertEquals(1, extraction.size, "exactly one binding point")
        assertEquals(
            listOf(PropositionRunLinkStore::class.java),
            extraction.single().parameterTypes.toList(),
        )
        assertEquals(
            IncrementalPropositionExtraction::class.java,
            extraction.single().returnType,
            "returns the receiver so a bean method can bind it in one expression",
        )
    }
}
