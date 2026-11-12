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
package com.embabel.dice.metamodel

import com.fasterxml.jackson.annotation.JsonIgnore
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant

/**
 * Checks that the plain-string context id accessors stay out of the JSON form of a drift report
 * and a drift check result.
 */
class DriftContextIdValueJsonTest {

    private val mapper = ObjectMapper().registerModule(JavaTimeModule())

    /**
     * Lists the top-level field names of a value once it has been serialized to JSON.
     *
     * @param value The object to serialize.
     * @return The field names, in no particular order.
     */
    private fun fieldNames(value: Any): Set<String> {
        val node: JsonNode = mapper.valueToTree(value)
        return node.fieldNames().asSequence().toSet()
    }

    @Test
    fun `a scoped drift report serializes without a contextIdValue field`() {
        val names = fieldNames(DriftContextIdFixtures.scopedReport("acme"))

        assertTrue(names.contains("schemaName"), "expected a schemaName field, got $names")
        assertFalse(names.contains("contextIdValue"), "contextIdValue must not be serialized, got $names")
    }

    @Test
    fun `a whole graph drift report serializes without a contextIdValue field`() {
        val report = DriftReport("support", "hash", emptySet(), emptySet(), Instant.EPOCH)

        val names = fieldNames(report)

        assertTrue(names.contains("schemaName"), "expected a schemaName field, got $names")
        assertFalse(names.contains("contextIdValue"), "contextIdValue must not be serialized, got $names")
    }

    @Test
    fun `a drift check result serializes without a contextIdValue field`() {
        val node: JsonNode = mapper.valueToTree(DriftContextIdFixtures.scopedResult("acme"))
        val names = node.fieldNames().asSequence().toSet()
        val reportNames = node.get("report").fieldNames().asSequence().toSet()

        assertTrue(names.contains("declaredVersion"), "expected a declaredVersion field, got $names")
        assertFalse(names.contains("contextIdValue"), "contextIdValue must not be serialized, got $names")
        assertFalse(
            reportNames.contains("contextIdValue"),
            "contextIdValue must not be serialized in the nested report, got $reportNames",
        )
    }

    @Test
    fun `both context id string getters are excluded from JSON`() {
        val getters: List<Class<*>> = listOf(DriftReport::class.java, DriftCheckResult::class.java)
        assertEquals(2, getters.size, "expected two context id getters in the list")

        for (type in getters) {
            assertTrue(
                type.getMethod("getContextIdValue").isAnnotationPresent(JsonIgnore::class.java),
                "${type.name}.getContextIdValue must carry @get:JsonIgnore so it stays out of JSON",
            )
        }
    }
}
