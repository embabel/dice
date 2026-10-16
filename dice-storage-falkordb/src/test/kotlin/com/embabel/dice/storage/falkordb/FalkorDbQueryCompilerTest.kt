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
package com.embabel.dice.storage.falkordb

import com.embabel.dice.proposition.ContextReadScope
import com.embabel.dice.proposition.PropositionQuery
import com.embabel.dice.proposition.ReadOrder
import com.embabel.dice.proposition.ScopedReadSpec
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FalkorDbQueryCompilerTest {
    @Test
    fun `untrusted values are parameters and hydration follows selection`() {
        val hostile = "x' MATCH (n) DELETE n //"
        val scope = ContextReadScope.ofValues(listOf(hostile))
        val spec = ScopedReadSpec.at(Instant.EPOCH, 2.0).restrict(scope, PropositionQuery(entityId = hostile))
        val read = FalkorDbQueryCompiler().scoped(scope, spec, ReadOrder.CREATED_DESC, 3)
        assertFalse(read.statement.contains(hostile))
        assertTrue(read.parameters.values.contains(hostile))
        assertTrue(read.statement.indexOf("LIMIT") < read.statement.indexOf("RETURN"))
        assertTrue(read.statement.contains("p.createdN DESC"))
    }

    @Test
    fun `UTF16 keys preserve portable ordering across supplementary characters`() {
        val values = listOf("z", "🙂", "\uE000", "𐀀", "a", "aa", "a\u0000")
        assertEquals(values.sorted(), values.sortedBy(FalkorDbPropositionCodec::orderKey))
    }

    @Test
    fun `negative native limits fail before dispatch`() {
        assertThrows<IllegalArgumentException> { FalkorDbQueryCompiler().legacy(PropositionQuery(limit = -1)) }
    }
}
