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
package com.embabel.dice.conformance.example;

import com.embabel.common.ai.model.EmbeddingService;
import com.embabel.dice.conformance.ConformanceEmbeddingService;
import com.embabel.dice.conformance.ConformanceEmbeddings;
import com.embabel.dice.proposition.store.InMemoryPropositionRepository;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Checks that the conformance embeddings and their service work when called from Java.
 */
class ConformanceEmbeddingsJavaInteropTest {

    @Test
    void aTextAtAnAngleEmbedsToItsCosineAndSine() {
        float[] vector = ConformanceEmbeddings.embed(ConformanceEmbeddings.text("query", 60.0), 8);

        assertEquals(8, vector.length);
        assertEquals(0.5f, vector[0], 1e-6f);
        assertEquals(0.8660254f, vector[1], 1e-6f);
    }

    @Test
    void theServiceEmbedsWithConformanceEmbeddings() {
        String text = ConformanceEmbeddings.text("query", 60.0);
        EmbeddingService service = new ConformanceEmbeddingService();

        assertEquals(8, service.getDimensions());
        assertArrayEquals(ConformanceEmbeddings.embed(text, 8), service.embed(text));
    }

    @Test
    void aStoreIsBuiltWithTheService() {
        InMemoryPropositionRepository store = new InMemoryPropositionRepository(new ConformanceEmbeddingService());

        assertTrue(store.getSupportsVector());
    }
}
