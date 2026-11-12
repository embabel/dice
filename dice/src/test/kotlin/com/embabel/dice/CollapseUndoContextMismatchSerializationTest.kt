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
package com.embabel.dice

import com.embabel.dice.spi.CollapseUndoContextMismatchException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.ObjectInputStream
import java.io.ObjectOutputStream
import java.io.ObjectStreamClass

/**
 * Checks that a context mismatch exception keeps the Java serialization form earlier releases
 * wrote, so an instance saved or sent by one release can be read by another.
 */
class CollapseUndoContextMismatchSerializationTest {

    @Test
    fun `the serial version id is the one earlier releases wrote`() {
        val id = ObjectStreamClass.lookup(CollapseUndoContextMismatchException::class.java).serialVersionUID

        assertEquals(
            4887747240668435430L,
            id,
            "the serial version id must stay fixed, or instances written by earlier releases stop reading",
        )
    }

    @Test
    fun `an instance written with Java serialization reads back with the same values`() {
        val original = ContextIdFixtures.collapseUndoContextMismatch("acme", "globex")

        val bytes = ByteArrayOutputStream()
        ObjectOutputStream(bytes).use { it.writeObject(original) }
        val copy = ObjectInputStream(ByteArrayInputStream(bytes.toByteArray())).use { it.readObject() }
            as CollapseUndoContextMismatchException

        assertEquals(original.message, copy.message)
        assertEquals("acme", copy.commandedContextIdValue)
        assertEquals("globex", copy.actualContextIdValue)
        assertEquals("proposition-1", copy.propositionId)
        assertEquals(original.commandedContextId, copy.commandedContextId)
        assertEquals(original.actualContextId, copy.actualContextId)
    }
}
