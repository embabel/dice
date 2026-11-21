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
package com.embabel.dice.audit

import com.embabel.agent.core.ContextId
import com.embabel.dice.proposition.Proposition
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.time.Instant

class InMemoryAuditLogTest {

    private val log = InMemoryAuditLog()
    private val cutover = Instant.parse("2026-01-02T00:00:00Z")
    private val proposition = Proposition(id = "old", contextId = ContextId("scope"), text = "Previously known", mentions = emptyList(), confidence = 0.8)

    @Test
    fun `unchanged claims are present from the complete baseline and earlier history is unavailable`() {
        assertThrows(AuditCoverageException::class.java) { log.beliefSetAsOf("scope", cutover) }

        log.initializeBaseline("scope", cutover, listOf(proposition))

        assertThrows(AuditCoverageException::class.java) { log.beliefSetAsOf("scope", cutover.minusNanos(1)) }
        assertEquals(listOf(AuditedProposition.from(proposition)), log.beliefSetAsOf("scope", cutover))
        assertThrows(AuditCoverageException::class.java) { log.beliefSetAsOf("other", cutover) }
    }
}
