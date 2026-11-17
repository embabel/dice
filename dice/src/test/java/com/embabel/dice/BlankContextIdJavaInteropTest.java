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

import com.embabel.dice.incremental.BookmarkKey;
import com.embabel.dice.incremental.HashKey;
import com.embabel.dice.projection.memory.collector.CollectorRunContext;
import com.embabel.dice.proposition.Proposition;
import com.embabel.dice.spi.CollapseUndoCommand;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Checks that the Java-facing factories reject a blank context id, the same way the
 * Kotlin constructors do, and still accept a real one.
 *
 * Contract:
 * R1: each factory's context id argument must not be blank. Blank means empty or only spaces.
 * R2: a non-blank context id is accepted and is readable back through getContextIdValue.
 * E1: a blank context id makes the factory throw IllegalArgumentException.
 * E2: a non-blank context id returns a value that carries that id.
 */
class BlankContextIdJavaInteropTest {

    // R1, E1: a blank context id is rejected by BookmarkKey.of.
    @Test
    void bookmarkKeyRejectsABlankContextId() {
        assertThrows(IllegalArgumentException.class, () -> BookmarkKey.of(" ", "s"));
    }

    // R1, E1: an empty string also counts as blank for HashKey.of.
    @Test
    void hashKeyRejectsAnEmptyContextId() {
        assertThrows(IllegalArgumentException.class, () -> HashKey.of("", "h"));
    }

    // R1, E1: a blank context id is rejected by CollectorRunContext.of.
    @Test
    void collectorRunContextRejectsABlankContextId() {
        assertThrows(IllegalArgumentException.class, () -> CollectorRunContext.of("r", " "));
    }

    // R1, E1: a blank context id is rejected by CollapseUndoCommand.of.
    @Test
    void collapseUndoCommandRejectsABlankContextId() {
        assertThrows(IllegalArgumentException.class, () -> CollapseUndoCommand.of(" ", "a", "b"));
    }

    // R1, E1: a blank context id is rejected by Proposition.draft.
    @Test
    void propositionDraftRejectsABlankContextId() {
        assertThrows(IllegalArgumentException.class, () -> Proposition.draft(" ", "t", 0.9));
    }

    // R2, E2: a real context id is accepted by every factory, the boundary on the other side of R1.
    @Test
    void factoriesAcceptANonBlankContextId() {
        BookmarkKey bookmark = BookmarkKey.of("acme", "s");
        HashKey hash = HashKey.of("acme", "h");
        CollectorRunContext run = CollectorRunContext.of("r", "acme");
        CollapseUndoCommand undo = CollapseUndoCommand.of("acme", "a", "b");

        assertEquals("acme", bookmark.getContextIdValue());
        assertEquals("acme", hash.getContextIdValue());
        assertEquals("acme", run.getContextIdValue());
        assertEquals("acme", undo.getContextIdValue());
        assertNotNull(Proposition.draft("acme", "t", 0.9));
    }
}
