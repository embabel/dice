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
