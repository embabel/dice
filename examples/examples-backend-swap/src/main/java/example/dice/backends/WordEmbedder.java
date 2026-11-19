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
package example.dice.backends;

import com.embabel.common.ai.model.EmbeddingService;
import com.embabel.common.ai.model.ModelType;
import com.embabel.common.ai.model.PricingModel;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;

/**
 * A stand-in for a real embedding model, so the example runs offline with no API key.
 *
 * <p>Each word of the text is hashed into one of {@value #DIMENSIONS} buckets and counted, and
 * the counts are scaled to length 1. The same text always gets the same vector, and sentences
 * that share words land near each other. It knows nothing about meaning.
 *
 * <p>Both stores need an embedding service to start. DICE uses the application's own
 * {@code EmbeddingService} bean when there is exactly one, or one marked {@code @Primary}.
 * This one is {@code @Primary} so it is still picked when a real provider key adds more.
 */
@Component
@Primary
class WordEmbedder implements EmbeddingService {

    /** How many numbers each vector has. */
    static final int DIMENSIONS = 16;

    @Override
    public String getName() {
        return "word-embedder";
    }

    @Override
    public String getProvider() {
        return "dice-examples";
    }

    @Override
    public int getDimensions() {
        return DIMENSIONS;
    }

    @Override
    public ModelType getType() {
        return ModelType.EMBEDDING;
    }

    @Override
    public PricingModel getPricingModel() {
        return PricingModel.Companion.getALL_YOU_CAN_EAT();
    }

    @Override
    public float[] embed(String text) {
        var vector = new float[DIMENSIONS];
        for (var word : text.toLowerCase(Locale.ROOT).split("[^\\p{L}\\p{N}]+")) {
            if (!word.isEmpty()) {
                vector[Math.floorMod(word.hashCode(), DIMENSIONS)] += 1f;
            }
        }
        var length = 0.0;
        for (var value : vector) {
            length += value * value;
        }
        if (length > 0) {
            var scale = (float) (1 / Math.sqrt(length));
            for (var i = 0; i < DIMENSIONS; i++) {
                vector[i] *= scale;
            }
        }
        return vector;
    }

    @Override
    public List<float[]> embed(List<String> texts) {
        return texts.stream().map(this::embed).toList();
    }
}
