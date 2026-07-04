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
package com.embabel.dice.bundle.support

import com.embabel.agent.core.ContextId
import com.embabel.dice.bundle.KnowledgeBundle
import com.embabel.dice.bundle.KnowledgeBundleExporter
import com.embabel.dice.proposition.PropositionRepository
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.slf4j.LoggerFactory
import java.io.OutputStream
import java.io.Writer

/**
 * Jackson-backed implementation of [KnowledgeBundleExporter].
 *
 * Uses `jacksonObjectMapper().findAndRegisterModules()` — the same configuration used
 * throughout the library — so that `Instant` fields on propositions serialise correctly.
 *
 * The mapper is created once at construction and shared across calls; it is
 * thread-safe after configuration.
 *
 * This implementation also supports repository-backed context-scoped export via
 * [exportScoped] and [exportAllContexts].
 */
class JacksonKnowledgeBundleExporter : KnowledgeBundleExporter {

    private val logger = LoggerFactory.getLogger(JacksonKnowledgeBundleExporter::class.java)

    private val mapper = jacksonObjectMapper().findAndRegisterModules()

    override fun exportToString(bundle: KnowledgeBundle): String {
        logger.info(
            "Exporting bundle: {} propositions, context={}",
            bundle.propositions.size,
            bundle.contextId,
        )
        return mapper.writeValueAsString(bundle)
    }

    override fun exportToStream(bundle: KnowledgeBundle, outputStream: OutputStream) {
        logger.info(
            "Exporting bundle to stream: {} propositions, context={}",
            bundle.propositions.size,
            bundle.contextId,
        )
        mapper.writeValue(outputStream, bundle)
        outputStream.flush()
    }

    override fun exportToWriter(bundle: KnowledgeBundle, writer: Writer) {
        logger.info(
            "Exporting bundle to writer: {} propositions, context={}",
            bundle.propositions.size,
            bundle.contextId,
        )
        mapper.writeValue(writer, bundle)
        writer.flush()
    }

    /**
     * Export propositions in a given context as a bundle.
     *
     * Reads all propositions belonging to [contextId] from the repository using the
     * context-scoped [PropositionRepository.findByContextIdValue] method, assembles them
     * into a single KnowledgeBundle with the given context ID, and returns the bundle.
     * Loads all matching propositions into memory — callers exporting very large contexts
     * should be aware of this and batch if needed.
     *
     * If no propositions exist for this context, returns a valid empty bundle with the
     * correct contextId.
     *
     * @param contextId The ID of the context to export.
     * @param repository The source of propositions.
     * @return A KnowledgeBundle containing all propositions in that context.
     */
    override fun exportScoped(contextId: String, repository: PropositionRepository): KnowledgeBundle {
        val propositions = repository.findByContextIdValue(contextId)
        logger.info(
            "Exporting context '{}': {} propositions",
            contextId,
            propositions.size,
        )
        return KnowledgeBundle.from(contextId, propositions)
    }

    /**
     * Export all contexts present in the repository, one bundle per context.
     *
     * Loads all propositions from the repository, groups them by context, and returns one
     * KnowledgeBundle per distinct context. If the repository is empty, returns an empty list.
     * All propositions for all contexts are loaded into memory at once.
     *
     * The order of returned bundles is unspecified.
     *
     * @param repository The source of propositions.
     * @return A list of KnowledgeBundles, one per context present in the repository.
     */
    override fun exportAllContexts(repository: PropositionRepository): List<KnowledgeBundle> {
        val allPropositions = repository.findAll()
        logger.debug("Exporting all contexts: {} total propositions", allPropositions.size)

        if (allPropositions.isEmpty()) {
            logger.info("No propositions in store; exportAll returns empty list")
            return emptyList()
        }

        // Group propositions by context
        val byContext = allPropositions.groupBy { it.contextId }
        logger.info("Found {} distinct context(s)", byContext.size)

        // Build one bundle per context
        return byContext.map { (contextId, propositions) ->
            logger.debug("Building bundle for context '{}': {} propositions", contextId.value, propositions.size)
            KnowledgeBundle.from(contextId, propositions)
        }
    }
}
