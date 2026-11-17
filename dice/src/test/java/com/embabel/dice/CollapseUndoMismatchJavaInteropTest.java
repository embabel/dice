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

import com.embabel.dice.spi.CollapseUndoContextMismatchException;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Checks that Java can build a context mismatch exception with {@code of} and that the result
 * behaves like one the library throws itself.
 */
class CollapseUndoMismatchJavaInteropTest {

    @Test
    void ofKeepsTheCommandedAndActualContextIds() {
        CollapseUndoContextMismatchException error =
                CollapseUndoContextMismatchException.of("a", "p", "b");

        assertEquals("a", error.getCommandedContextIdValue());
        assertEquals("b", error.getActualContextIdValue());
        assertEquals("p", error.getPropositionId());
    }

    @Test
    void ofWordsTheMessageTheSameWayTheLibraryDoes() {
        CollapseUndoContextMismatchException error =
                CollapseUndoContextMismatchException.of("a", "p", "b");

        assertEquals(
                "Proposition p belongs to context b, and the undo was issued for context a; refusing to touch it",
                error.getMessage());
    }

    @Test
    void ofMatchesTheFixtureBuiltExceptionMessage() {
        // The fixture always uses the proposition id "proposition-1", so build the Java one with that id too.
        CollapseUndoContextMismatchException fromOf =
                CollapseUndoContextMismatchException.of("a", "proposition-1", "b");
        CollapseUndoContextMismatchException fromFixture =
                ContextIdFixtures.collapseUndoContextMismatch("a", "b");

        assertEquals(fromFixture.getMessage(), fromOf.getMessage());
    }

    @Test
    void ofSurvivesJavaSerialization() throws IOException, ClassNotFoundException {
        CollapseUndoContextMismatchException original =
                CollapseUndoContextMismatchException.of("a", "p", "b");

        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ObjectOutputStream out = new ObjectOutputStream(bytes)) {
            out.writeObject(original);
        }
        CollapseUndoContextMismatchException copy;
        try (ObjectInputStream in = new ObjectInputStream(new ByteArrayInputStream(bytes.toByteArray()))) {
            copy = (CollapseUndoContextMismatchException) in.readObject();
        }

        assertEquals(original.getMessage(), copy.getMessage());
        assertEquals("a", copy.getCommandedContextIdValue());
        assertEquals("p", copy.getPropositionId());
        assertEquals("b", copy.getActualContextIdValue());
    }
}
