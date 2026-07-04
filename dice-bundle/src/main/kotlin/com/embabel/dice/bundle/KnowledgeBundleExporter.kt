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

import com.embabel.dice.proposition.PropositionRepository
import java.io.OutputStream
import java.io.Writer

/**
 * SPI for serialising a [KnowledgeBundle] to an external representation.
 *
 * The default implementation ([support.JacksonKnowledgeBundleExporter]) uses Jackson JSON.
 * Callers that need a different format (e.g., CBOR, Protobuf) provide their own implementation.
 *
 * Implementations may also optionally support context-scoped export by implementing the
 * [exportScoped] and [exportAllContexts] methods, which read propositions from a repository
 * and assemble bundles.
 */
interface KnowledgeBundleExporter {

    /**
     * Serialise [bundle] to a self-contained JSON string.
     *
     * @param bundle The bundle to serialise.
     * @return A UTF-8 string representation of the bundle.
     */
    fun exportToString(bundle: KnowledgeBundle): String

    /**
     * Serialise [bundle], writing the result to [outputStream].
     * The stream is flushed before this method returns but is NOT closed —
     * the caller is responsible for closing it.
     *
     * @param bundle The bundle to serialise.
     * @param outputStream Target stream. Caller is responsible for closing.
     */
    fun exportToStream(bundle: KnowledgeBundle, outputStream: OutputStream)

    /**
     * Serialise [bundle], writing the result to [writer].
     * The writer is flushed before this method returns but is NOT closed —
     * the caller is responsible for closing it.
     *
     * @param bundle The bundle to serialise.
     * @param writer Target writer. Caller is responsible for closing.
     */
    fun exportToWriter(bundle: KnowledgeBundle, writer: Writer)

    /**
     * Export all propositions in a given context as a [KnowledgeBundle].
     *
     * Reads propositions for the context from [repository] and assembles them into a single
     * bundle with the given contextId. If no propositions exist for this context, returns
     * an empty but valid bundle (zero propositions, correct contextId).
     *
     * The default implementation is a no-op that returns null; implementations that support
     * repository-backed export must override this method.
     *
     * @param contextId The ID of the context to export.
     * @param repository The source of propositions.
     * @return A [KnowledgeBundle] containing all propositions in that context, or null if
     *   repository-backed export is not supported.
     */
    fun exportScoped(contextId: String, repository: PropositionRepository): KnowledgeBundle? =
        null

    /**
     * Export all contexts present in the store, returning one bundle per context.
     *
     * Scans the repository for all propositions, derives the set of distinct context IDs,
     * and returns one [KnowledgeBundle] per context. If the repository is empty, returns
     * an empty list.
     *
     * The default implementation is a no-op that returns null; implementations that support
     * repository-backed export must override this method.
     *
     * @param repository The source of propositions.
     * @return A list of [KnowledgeBundle], one per context (order unspecified), or null if
     *   repository-backed export is not supported.
     */
    fun exportAllContexts(repository: PropositionRepository): List<KnowledgeBundle>? =
        null
}
