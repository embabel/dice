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
package com.embabel.dice.bundle

import com.embabel.agent.core.ContextId
import com.embabel.dice.bundle.support.JacksonKnowledgeBundleExporter
import com.embabel.dice.bundle.support.JacksonKnowledgeBundleImporter
import com.embabel.dice.proposition.Proposition
import com.embabel.dice.proposition.store.InMemoryPropositionRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class KnowledgeBundleMetamodelCompatibilityTest {

    private val contextId = ContextId("metamodel-context")
    private val exporter = JacksonKnowledgeBundleExporter()

    private fun proposition() = Proposition(
        contextId = contextId,
        text = "Ada designed an analytical engine program",
        mentions = emptyList(),
        confidence = 0.9,
    )

    @Test
    fun `metamodel content hash survives export and import`() {
        val bundle = KnowledgeBundle.from(
            contextId = contextId,
            propositions = listOf(proposition()),
            metamodelContentHash = "source-hash",
        )

        val outcome = JacksonKnowledgeBundleImporter().importFromString(
            exporter.exportToString(bundle),
            InMemoryPropositionRepository(),
        )

        val success = assertInstanceOf(BundleImportOutcome.Success::class.java, outcome)
        assertEquals("source-hash", success.bundle.metamodelContentHash)
    }

    @Test
    fun `missing metamodel content hash remains null`() {
        val bundle = KnowledgeBundle.from(contextId, listOf(proposition()))
        val jsonWithoutHash = exporter.exportToString(bundle).replace(
            Regex(",?\"metamodelContentHash\":null"),
            "",
        )

        val outcome = JacksonKnowledgeBundleImporter().importFromString(
            jsonWithoutHash,
            InMemoryPropositionRepository(),
        )

        val success = assertInstanceOf(BundleImportOutcome.Success::class.java, outcome)
        assertNull(success.bundle.metamodelContentHash)
        assertTrue(success.result.advisories.isEmpty())
    }

    @Test
    fun `assembler stamps the context metamodel hash when available`() {
        val repository = InMemoryPropositionRepository()
        repository.save(proposition())
        val requestedContexts = mutableListOf<ContextId>()
        val assembler = KnowledgeBundleAssembler(
            repository = repository,
            metamodelContentHashSource = MetamodelContentHashSource { requestedContext ->
                requestedContexts += requestedContext
                "declared-hash"
            },
        )

        val bundle = assembler.forContext(contextId.value)

        assertEquals("declared-hash", bundle.metamodelContentHash)
        assertEquals(listOf(contextId), requestedContexts)
    }

    @Test
    fun `mismatched target metamodel produces an advisory and still imports`() {
        val bundle = KnowledgeBundle.from(
            contextId = contextId,
            propositions = listOf(proposition()),
            metamodelContentHash = "source-hash",
        )
        val importer = JacksonKnowledgeBundleImporter(
            metamodelContentHashSource = MetamodelContentHashSource { "target-hash" },
        )

        val outcome = importer.importFromString(
            exporter.exportToString(bundle),
            InMemoryPropositionRepository(),
        )

        val success = assertInstanceOf(BundleImportOutcome.Success::class.java, outcome)
        assertEquals(1, success.result.imported)
        val advisory = assertInstanceOf(
            BundleImportAdvisory.MetamodelMismatch::class.java,
            success.result.advisories.single(),
        )
        assertEquals(contextId, advisory.contextId)
        assertEquals("source-hash", advisory.bundleContentHash)
        assertEquals("target-hash", advisory.targetContentHash)
    }

    @Test
    fun `matching metamodel hashes produce no advisory`() {
        val bundle = KnowledgeBundle.from(
            contextId = contextId,
            propositions = listOf(proposition()),
            metamodelContentHash = "same-hash",
        )
        val importer = JacksonKnowledgeBundleImporter(
            metamodelContentHashSource = MetamodelContentHashSource { "same-hash" },
        )

        val outcome = importer.importFromString(
            exporter.exportToString(bundle),
            InMemoryPropositionRepository(),
        )

        val success = assertInstanceOf(BundleImportOutcome.Success::class.java, outcome)
        assertTrue(success.result.advisories.isEmpty())
    }
}
