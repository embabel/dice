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
package com.embabel.dice.evidence;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Shows how a Java caller builds each kind of evidence and what each kind accepts.
 */
class EvidenceKindsJavaInteropTest {

    private final EvidenceItem source1 =
            EvidenceItem.of("Ada worked with Babbage", Citation.of("p-1", "support-team"), 0.8);

    private final EvidenceItem source2 =
            EvidenceItem.of("Ada wrote notes on the engine", Citation.of("p-2", "support-team"), 0.8);

    private final Evidence.Derived derived =
            Evidence.derived("Ada was a programmer", 0.7, DerivationRule.of("prolog"), List.of("p-2"));

    @Test
    void inputOutsideTheInputIdsIsRejected() {
        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                () -> derived.withInputs(List.of(source1)));

        assertTrue(thrown.getMessage().contains("inputs[0]"), thrown.getMessage());
        assertTrue(thrown.getMessage().contains("p-1"), thrown.getMessage());
    }

    @Test
    void inputsMayBeASubsetOfTheInputIds() {
        Evidence.Derived twoIds =
                Evidence.derived("Ada was a programmer", 0.7, DerivationRule.of("prolog"), List.of("p-1", "p-2"));

        Evidence.Derived withInputs = twoIds.withInputs(List.of(source2));

        assertEquals(List.of(source2), withInputs.getInputs());
        assertEquals(List.of("p-1", "p-2"), withInputs.getInputIds());
    }
}
