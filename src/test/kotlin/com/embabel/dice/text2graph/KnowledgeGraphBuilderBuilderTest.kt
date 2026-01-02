package com.embabel.dice.text2graph

import com.embabel.agent.api.common.Ai
import com.embabel.agent.core.DataDictionary
import com.embabel.agent.rag.model.Chunk
import com.embabel.dice.common.SourceAnalysisConfig
import com.embabel.dice.common.resolver.AlwaysCreateEntityResolver
import com.embabel.dice.text2graph.builder.InMemoryObjectGraphGraphProjector
import com.embabel.dice.text2graph.builder.KnowledgeGraphBuilders
import com.embabel.dice.text2graph.support.LlmSourceAnalyzer
import io.mockk.mockk
import org.junit.jupiter.api.Test

val dd = DataDictionary.fromClasses()

class KnowledgeGraphBuilderBuilderTest {

    @Test
    fun testSimple() {
        val mockAi = mockk<Ai>()
        val sourceAnalyzer = LlmSourceAnalyzer(
            mockAi,
        )
        val kgb = KnowledgeGraphBuilders
            .withSourceAnalyzer(sourceAnalyzer)
            .withEntityResolver(AlwaysCreateEntityResolver)
            .knowledgeGraphBuilder()

        val chunks = listOf<Chunk>()
        val sourceAnalysisConfig = SourceAnalysisConfig(
            directions = "Bar",
            schema = dd,
            entityResolver = AlwaysCreateEntityResolver,
        )
        val projector = InMemoryObjectGraphGraphProjector()

        val delta = kgb.computeDelta(chunks, sourceAnalysisConfig)
        delta?.let { projector.project(dd, it) }
    }

}