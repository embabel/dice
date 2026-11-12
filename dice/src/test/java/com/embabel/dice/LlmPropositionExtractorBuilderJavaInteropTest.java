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
package com.embabel.dice;

import com.embabel.agent.api.common.Ai;
import com.embabel.common.ai.model.LlmOptions;
import com.embabel.dice.common.SchemaAdherence;
import com.embabel.dice.proposition.extraction.LlmPropositionExtractor;
import com.embabel.dice.proposition.extraction.LlmPropositionExtractorBuilder;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;

class LlmPropositionExtractorBuilderJavaInteropTest {

    @Test
    void builderProducesSameExtractorAsWithLlm() {
        LlmOptions llmOptions = mock(LlmOptions.class);
        Ai ai = mock(Ai.class);

        LlmPropositionExtractor fromBuilder = LlmPropositionExtractor.builder(llmOptions).withAi(ai);
        LlmPropositionExtractor fromWithLlm = LlmPropositionExtractor.withLlm(llmOptions).withAi(ai);

        assertEquals(fromWithLlm, fromBuilder);
    }

    @Test
    void builderCanBeHeldInTypedVariable() {
        LlmOptions llmOptions = mock(LlmOptions.class);
        Ai ai = mock(Ai.class);

        LlmPropositionExtractorBuilder builder = LlmPropositionExtractor.builder(llmOptions);
        LlmPropositionExtractor extractor = builder.withAi(ai);

        assertEquals(LlmPropositionExtractor.withLlm(llmOptions).withAi(ai), extractor);
    }

    @Test
    void extractorFromBuilderAcceptsSchemaAdherenceChange() {
        LlmOptions llmOptions = mock(LlmOptions.class);
        Ai ai = mock(Ai.class);

        LlmPropositionExtractor extractor = LlmPropositionExtractor.builder(llmOptions)
                .withAi(ai)
                .withSchemaAdherence(SchemaAdherence.STRICT);

        assertEquals(SchemaAdherence.STRICT, extractor.getSchemaAdherence());
    }
}
