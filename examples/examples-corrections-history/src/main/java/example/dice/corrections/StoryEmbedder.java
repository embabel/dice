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
package example.dice.corrections;

import com.embabel.common.ai.model.EmbeddingService;
import com.embabel.common.ai.model.PricingModel;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Stands in for a real embedding model so the story is repeatable. Each sentence the story
 * uses has a fixed vector, so which stored facts the reviser sees as similar never changes
 * from run to run.
 *
 * <p>Every story sentence lies on a flat plane at its own angle, and the vector is
 * {@code [cos a, sin a, 0]}. Two sentences are as similar as the cosine of the angle between
 * them. "Acme pays for the Pro plan" sits 25 degrees from "Acme is on the Pro plan", so they
 * score 0.91. The invoice sentences sit far from the plan sentences, so the two topics never
 * score above 0.5 against each other. Any other text points straight up out of the plane and
 * is unlike every story sentence.
 *
 * <p>Sentences are matched after trimming and ignoring case.
 */
final class StoryEmbedder implements EmbeddingService {

    /** The angle of each story sentence on the plane, in degrees. */
    private static final Map<String, Double> ANGLES = Map.of(
            "acme is on the pro plan", 0.0,
            "acme pays for the pro plan", 25.0,
            "acme is a paying customer", -40.0,
            "acme's invoices are paid annually", 120.0,
            "acme's invoices are paid monthly", 145.0,
            "acme moved to the basic plan", 40.0);

    /** Where any sentence outside the story lands. */
    private static final float[] ELSEWHERE = {0f, 0f, 1f};

    @Override
    public String getName() {
        return "story-embedder";
    }

    @Override
    public String getProvider() {
        return "dice-examples";
    }

    @Override
    public PricingModel getPricingModel() {
        return PricingModel.getALL_YOU_CAN_EAT();
    }

    @Override
    public int getDimensions() {
        return 3;
    }

    /**
     * @param text a sentence
     * @return its fixed vector, or one unlike every story sentence when the text is not in the
     *         story
     */
    @Override
    public float[] embed(String text) {
        var degrees = ANGLES.get(text.strip().toLowerCase(Locale.ROOT));
        if (degrees == null) {
            return ELSEWHERE.clone();
        }
        var radians = Math.toRadians(degrees);
        return new float[] {(float) Math.cos(radians), (float) Math.sin(radians), 0f};
    }

    @Override
    public List<float[]> embed(List<String> texts) {
        return texts.stream().map(this::embed).toList();
    }
}
