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
package com.embabel.dice.text2graph.builder

import com.embabel.agent.rag.model.Chunk
import com.embabel.dice.common.EntityResolver
import com.embabel.dice.common.SourceAnalysisContext
import com.embabel.dice.text2graph.KnowledgeGraphBuilder
import com.embabel.dice.text2graph.SourceAnalyzer
import com.embabel.dice.text2graph.support.MultiPassKnowledgeGraphBuilder

/**
 * Convenient API for building knowledge graphs
 */
object KnowledgeGraphBuilders {

    @JvmStatic
    fun withSourceAnalyzer(sourceAnalyzer: SourceAnalyzer) =
        EntityResolverSetting(sourceAnalyzer)

}

class EntityResolverSetting(
    val sourceAnalyzer: SourceAnalyzer,
) {

    fun withEntityResolver(entityResolver: EntityResolver): KgbBuilder =
        KgbBuilder(sourceAnalyzer, entityResolver)
}

class KgbBuilder(
    val sourceAnalyzer: SourceAnalyzer,
    val entityResolver: EntityResolver,
    private val graphProjector: GraphProjector<Any> = InMemoryObjectGraphGraphProjector(),
) {

    fun knowledgeGraphBuilder(): KnowledgeGraphBuilder {
        return MultiPassKnowledgeGraphBuilder(
            sourceAnalyzer = sourceAnalyzer,
            entityResolver = entityResolver,
        )
    }

    fun projector(): ToObjects {
        return ToObjects()
    }

    inner class ToObjects {

        /**
         * Project the given chunks into objects according to the provided source analysis config.
         * Return a list of domain objects. Objects will have their own relationships.
         */
        fun project(
            chunks: List<Chunk>,
            sourceAnalysisContext: SourceAnalysisContext
        ): List<Any> {
            val kbg = knowledgeGraphBuilder()
            val delta = kbg.computeDelta(chunks, sourceAnalysisContext)
            return graphProjector.project(sourceAnalysisContext.schema, delta)
        }

        /**
         * Return the root object
         */
        fun <T> root(
            chunks: List<Chunk>,
            sourceAnalysisContext: SourceAnalysisContext,
            clazz: Class<T>,
            predicate: (T) -> Boolean,
        ): T? {
            val objects = project(chunks, sourceAnalysisContext)
            return objects
                .filterIsInstance(clazz)
                .firstOrNull { predicate(it) }
        }

    }
}
