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

import com.embabel.agent.rag.model.Chunk
import com.embabel.dice.common.SourceAnalysisContext
import com.embabel.dice.common.SuggestedEntities
import com.embabel.dice.entity.EntityExtractor

/**
 * Adapter that wraps a [SourceAnalyzer] as an [EntityExtractor].
 *
 * Useful when you have an existing SourceAnalyzer but only need entity extraction.
 */
class SourceAnalyzerEntityExtractor(
    private val sourceAnalyzer: SourceAnalyzer,
) : EntityExtractor {

    override fun suggestEntities(
        chunk: Chunk,
        context: SourceAnalysisContext,
    ): SuggestedEntities = sourceAnalyzer.suggestEntities(chunk, context)
}
