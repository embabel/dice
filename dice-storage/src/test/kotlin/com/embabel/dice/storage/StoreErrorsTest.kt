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
import org.sqlite.SQLiteErrorCode
import org.sqlite.SQLiteException
import java.sql.SQLException

/**
 * Proves [StoreErrors.isUniquenessViolation] answers off the driver's own code, Neo4j's status code
 * or SQLite's result code, and only that code, wherever it turns up in a cause chain.
 */
class StoreErrorsTest {

    private val constraintViolationCode = "Neo.ClientError.Schema.ConstraintValidationFailed"

    @Test
    fun `a ClientException carrying the constraint code answers true`() {
        val error = ClientException(constraintViolationCode, "Node already exists with label ...")

        assertTrue(StoreErrors.isUniquenessViolation(error))
    }

    @Test
    fun `a wrapping exception whose cause carries the code answers true`() {
        val cause = ClientException(constraintViolationCode, "Node already exists with label ...")
        val wrapper = RuntimeException("save failed", cause)

        assertTrue(StoreErrors.isUniquenessViolation(wrapper))
    }

    @Test
    fun `a ClientException with a different code answers false`() {
        val error = ClientException("Neo.ClientError.Statement.SyntaxError", "bad Cypher")

        assertFalse(StoreErrors.isUniquenessViolation(error))
    }

    @Test
    fun `a message that merely says already exists answers false`() {
        val error = RuntimeException("Node(0) already exists with label ExtractionRunTerminalWrite")

        assertFalse(StoreErrors.isUniquenessViolation(error))
    }

    @Test
    fun `a self-referential cause chain terminates`() {
        val first = RuntimeException("first")
        val second = RuntimeException("second", first)
        first.initCause(second)

        assertFalse(StoreErrors.isUniquenessViolation(first))
    }

    @Test
    fun `a SQLite UNIQUE constraint failure answers true`() {
        val error = SQLiteException("UNIQUE constraint failed: n.key", SQLiteErrorCode.SQLITE_CONSTRAINT_UNIQUE)

        assertTrue(StoreErrors.isUniquenessViolation(RuntimeException("save failed", error)))
    }

    @Test
    fun `a SQLite PRIMARY KEY constraint failure answers true`() {
        val error = SQLiteException("UNIQUE constraint failed: n.id", SQLiteErrorCode.SQLITE_CONSTRAINT_PRIMARYKEY)

        assertTrue(StoreErrors.isUniquenessViolation(error))
    }

    @Test
    fun `a uniqueness failure inside one of Drivine's SQLite functions answers true`() {
        // The message SQLite gives when the write inside the function hits a unique index.
        val error = SQLiteException(
            "[SQLITE_ERROR] SQL error or missing database (org.sqlite.SQLiteException: " +
                "[SQLITE_CONSTRAINT_UNIQUE] A UNIQUE constraint failed (UNIQUE constraint failed: index 'uq'))",
            SQLiteErrorCode.SQLITE_ERROR,
        )

        assertTrue(StoreErrors.isUniquenessViolation(error))
    }

    @Test
    fun `any other failure inside one of Drivine's SQLite functions answers false`() {
        val error = SQLiteException(
            "[SQLITE_ERROR] SQL error or missing database (org.sqlite.SQLiteException: " +
                "[SQLITE_CONSTRAINT_NOTNULL] A NOT NULL constraint failed (NOT NULL constraint failed: n.key))",
            SQLiteErrorCode.SQLITE_ERROR,
        )

        assertFalse(StoreErrors.isUniquenessViolation(error))
    }

    @Test
    fun `a uniqueness code later in another failure's text answers false`() {
        val error = SQLiteException(
            "[SQLITE_ERROR] SQL error or missing database (org.sqlite.SQLiteException: " +
                "[SQLITE_CONSTRAINT_TRIGGER] A RAISE function within a trigger fired " +
                "(rejected: looks like [SQLITE_CONSTRAINT_UNIQUE]))",
            SQLiteErrorCode.SQLITE_ERROR,
        )

        assertFalse(StoreErrors.isUniquenessViolation(error))
    }

    @Test
    fun `a uniqueness code quoted without the driver's exception name answers true`() {
        val error = SQLiteException(
            "[SQLITE_ERROR] SQL error or missing database ([SQLITE_CONSTRAINT_UNIQUE] A UNIQUE constraint failed " +
                "(UNIQUE constraint failed: index 'uq'))",
            SQLiteErrorCode.SQLITE_ERROR,
        )

        assertTrue(StoreErrors.isUniquenessViolation(error))
    }

    @Test
    fun `SQLite's uniqueness message on an exception with no code answers true`() {
        val error = SQLException("UNIQUE constraint failed: index 'uq'")

        assertTrue(StoreErrors.isUniquenessViolation(RuntimeException("save failed", error)))
    }

    @Test
    fun `SQLite's uniqueness message after another quoted code answers false`() {
        val error = SQLiteException(
            "[SQLITE_ERROR] SQL error or missing database ([SQLITE_CONSTRAINT_TRIGGER] A RAISE function within a " +
                "trigger fired (rejected: UNIQUE constraint failed))",
            SQLiteErrorCode.SQLITE_ERROR,
        )

        assertFalse(StoreErrors.isUniquenessViolation(error))
    }

    @Test
    fun `a read-only refusal quoted without the driver's exception name is recognised`() {
        val error = SQLiteException(
            "[SQLITE_ERROR] SQL error or missing database ([SQLITE_READONLY] Attempt to write a readonly database)",
            SQLiteErrorCode.SQLITE_ERROR,
        )

        assertTrue(StoreErrors.isSqliteReadOnlyRefusal(error))
    }

    @Test
    fun `another SQLite constraint failure answers false`() {
        val error = SQLiteException("NOT NULL constraint failed: n.key", SQLiteErrorCode.SQLITE_CONSTRAINT_NOTNULL)

        assertFalse(StoreErrors.isUniquenessViolation(error))
    }

    @Test
    fun `a busy SQLite file answers false`() {
        val error = SQLiteException("database is locked", SQLiteErrorCode.SQLITE_BUSY)

        assertFalse(StoreErrors.isUniquenessViolation(error))
    }
}
