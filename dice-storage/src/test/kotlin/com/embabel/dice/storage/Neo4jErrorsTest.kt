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
package com.embabel.dice.storage

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.neo4j.driver.exceptions.ClientException

/**
 * Proves [Neo4jErrors.isUniquenessViolation] answers off the driver's status code, and only that
 * code, wherever it turns up in a cause chain.
 */
class Neo4jErrorsTest {

    private val constraintViolationCode = "Neo.ClientError.Schema.ConstraintValidationFailed"

    @Test
    fun `a ClientException carrying the constraint code answers true`() {
        val error = ClientException(constraintViolationCode, "Node already exists with label ...")

        assertTrue(Neo4jErrors.isUniquenessViolation(error))
    }

    @Test
    fun `a wrapping exception whose cause carries the code answers true`() {
        val cause = ClientException(constraintViolationCode, "Node already exists with label ...")
        val wrapper = RuntimeException("save failed", cause)

        assertTrue(Neo4jErrors.isUniquenessViolation(wrapper))
    }

    @Test
    fun `a ClientException with a different code answers false`() {
        val error = ClientException("Neo.ClientError.Statement.SyntaxError", "bad Cypher")

        assertFalse(Neo4jErrors.isUniquenessViolation(error))
    }

    @Test
    fun `a message that merely says already exists answers false`() {
        val error = RuntimeException("Node(0) already exists with label ExtractionRunTerminalWrite")

        assertFalse(Neo4jErrors.isUniquenessViolation(error))
    }

    @Test
    fun `a self-referential cause chain terminates`() {
        val first = RuntimeException("first")
        val second = RuntimeException("second", first)
        first.initCause(second)

        assertFalse(Neo4jErrors.isUniquenessViolation(first))
    }
}
