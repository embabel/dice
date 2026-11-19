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

import org.neo4j.driver.exceptions.Neo4jException
import java.sql.SQLException

/**
 * Tells a uniqueness constraint violation apart from every other kind of failure, by the driver's
 * own code: Neo4j's status code or SQLite's result code.
 *
 * Every [Neo4jException] carries a `code()` straight from the server, and a uniqueness constraint
 * violation always carries the same one: [CONSTRAINT_VALIDATION_FAILED]. SQLite's driver puts the
 * extended result code on its exception, and a uniqueness failure is `SQLITE_CONSTRAINT_UNIQUE`, or
 * `SQLITE_CONSTRAINT_PRIMARYKEY` for a key. Those codes are contracts the engines publish, so they
 * hold across driver versions in a way an exception message never can, since a message is free text
 * a future release can reword without warning. Drivine does not translate driver exceptions into
 * Spring's `DataAccessException` hierarchy, so there is no Spring type to catch here either. This
 * check is what the stores in this module use to tell "someone else already wrote the same thing"
 * apart from a real failure.
 *
 * SQLite's driver is optional, so its exception is recognised by name rather than by type: a store
 * on Neo4j alone runs without the class on its classpath.
 *
 * Drivine writes to SQLite from inside a SQL function of its own, and SQLite reports any failure
 * there as `SQLITE_ERROR`, keeping only the message the function failed with. The driver starts
 * every message with its code's name in brackets, so for `SQLITE_ERROR` the code the write really
 * failed with is the first bracketed name quoted after the error's own, however the quote is worded.
 * Where no code is quoted at all, as on an exception that kept only SQLite's own text, a uniqueness
 * failure is told by the text SQLite gives every one of them, `UNIQUE constraint failed`.
 *
 * Drivine's SQLite schema grammar has a check of its own, but it looks for that text in any
 * message of the chain and is meant for telling why a constraint could not be made. A trigger that
 * quotes the text would pass it, so the quoted code, where there is one, decides here.
 */
internal object StoreErrors {

    private const val CONSTRAINT_VALIDATION_FAILED = "Neo.ClientError.Schema.ConstraintValidationFailed"

    private const val SQLITE_EXCEPTION = "org.sqlite.SQLiteException"

    private const val SQLITE_ERROR = "SQLITE_ERROR"

    private const val SQLITE_READONLY = "SQLITE_READONLY"

    private val SQLITE_UNIQUENESS_CODES = setOf("SQLITE_CONSTRAINT_UNIQUE", "SQLITE_CONSTRAINT_PRIMARYKEY")

    /** A SQLite result code's name in brackets, as the driver starts each message with one. */
    private val QUOTED_SQLITE_CODE = Regex("""\[(SQLITE_[A-Z_]+)]""")

    /** What SQLite says of every UNIQUE or PRIMARY KEY failure. */
    private const val SQLITE_UNIQUENESS_TEXT = "UNIQUE constraint failed"

    /**
     * Walks the cause chain looking for a Neo4j or SQLite exception that carries a uniqueness code.
     *
     * Stops the moment it revisits a cause it has already seen, so a cause chain that loops back on
     * itself cannot spin forever.
     */
    fun isUniquenessViolation(error: Throwable?): Boolean {
        var current: Throwable? = error
        val seen = mutableSetOf<Throwable>()
        while (current != null && seen.add(current)) {
            if (current is Neo4jException && current.code() == CONSTRAINT_VALIDATION_FAILED) {
                return true
            }
            if (isSqliteUniquenessFailure(current)) {
                return true
            }
            current = current.cause
        }
        return false
    }

    /**
     * Whether SQLite refused a write because the connection is read-only, anywhere in the cause chain,
     * either as the driver's own code or quoted inside a `SQLITE_ERROR`.
     */
    fun isSqliteReadOnlyRefusal(error: Throwable?): Boolean {
        var current: Throwable? = error
        val seen = mutableSetOf<Throwable>()
        while (current != null && seen.add(current)) {
            val code = sqliteResultCode(current)
            val inner = if (code == SQLITE_ERROR) quotedCode(current) else null
            if (code?.startsWith(SQLITE_READONLY) == true || inner?.startsWith(SQLITE_READONLY) == true) return true
            current = current.cause
        }
        return false
    }

    /**
     * A SQLite exception whose own code is a uniqueness failure, or a `SQLITE_ERROR`, or an
     * exception of another JDBC driver's or wrapper's, whose quoted code is one. With no code quoted,
     * SQLite's own text for a uniqueness failure decides.
     */
    private fun isSqliteUniquenessFailure(error: Throwable): Boolean {
        val code = sqliteResultCode(error)
        if (code in SQLITE_UNIQUENESS_CODES) return true
        if (code != SQLITE_ERROR && (code != null || error !is SQLException)) return false
        quotedCode(error)?.let { return it in SQLITE_UNIQUENESS_CODES }
        return error.message.orEmpty().contains(SQLITE_UNIQUENESS_TEXT)
    }

    /** The first result code [error]'s message quotes after any it opens with, or null when it quotes none. */
    private fun quotedCode(error: Throwable): String? {
        val message = error.message ?: return null
        val own = QUOTED_SQLITE_CODE.matchAt(message, 0)
        return QUOTED_SQLITE_CODE.find(message, own?.range?.last?.plus(1) ?: 0)?.groupValues?.get(1)
    }

    /** The extended result code on a SQLite driver exception, or null for any other exception. */
    private fun sqliteResultCode(error: Throwable): String? {
        if (error.javaClass.name != SQLITE_EXCEPTION) return null
        return runCatching { (error.javaClass.getMethod("getResultCode").invoke(error) as? Enum<*>)?.name }.getOrNull()
    }
}
