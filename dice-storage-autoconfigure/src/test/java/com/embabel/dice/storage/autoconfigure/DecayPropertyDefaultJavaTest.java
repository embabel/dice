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
package com.embabel.dice.storage.autoconfigure;

import com.embabel.dice.proposition.Proposition;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Checks that a Java caller who builds the decay settings without naming k gets the same
 * decay rate that Proposition uses by default.
 *
 * Contract:
 * E1: new DiceStoreProperties.Decay().getK() returns Proposition.DEFAULT_DECAY_K.
 */
class DecayPropertyDefaultJavaTest {

    /** E1: the default store setting for k matches the constant on Proposition. */
    @Test
    void defaultDecayKMatchesPropositionConstant() {
        assertEquals(Proposition.DEFAULT_DECAY_K, new DiceStoreProperties.Decay().getK());
    }
}
