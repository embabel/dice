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
package com.embabel.dice.projection.memory

import com.embabel.dice.common.KnowledgeType
import com.embabel.dice.proposition.Proposition

/**
 * Strategy interface for classifying propositions into knowledge types.
 * Implementations can use different heuristics based on domain needs.
 */
fun interface KnowledgeTypeClassifier {

    /**
     * Classify a proposition into a knowledge type.
     * @param proposition The proposition to classify
     * @return The inferred knowledge type
     */
    fun classify(proposition: Proposition): KnowledgeType
}
