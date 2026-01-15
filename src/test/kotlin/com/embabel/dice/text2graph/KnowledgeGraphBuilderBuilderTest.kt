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
package com.embabel.dice.text2graph

import com.embabel.agent.api.common.Ai
import com.embabel.agent.core.ContextId
import com.embabel.agent.core.DataDictionary
import com.embabel.agent.rag.model.Chunk
import com.embabel.dice.common.SourceAnalysisContext
import com.embabel.dice.common.resolver.AlwaysCreateEntityResolver
import com.embabel.dice.text2graph.builder.InMemoryObjectGraphGraphProjector
import com.embabel.dice.text2graph.builder.KnowledgeGraphBuilders
import com.embabel.dice.text2graph.support.LlmSourceAnalyzer
import io.mockk.mockk
import org.junit.jupiter.api.Test

val dd = DataDictionary.fromClasses("test")
private val testContextId = ContextId("test-context")

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
        val sourceAnalysisContext = SourceAnalysisContext(
            schema = dd,
            entityResolver = AlwaysCreateEntityResolver,
            contextId = testContextId,
        )
        val projector = InMemoryObjectGraphGraphProjector()

        val delta = kgb.computeDelta(chunks, sourceAnalysisContext)
        delta?.let { projector.project(dd, it) }
    }

}
