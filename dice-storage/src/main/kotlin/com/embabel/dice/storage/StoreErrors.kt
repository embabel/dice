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
 * failed with is read from the bracketed name that opens the quoted exception, and nowhere else.
 */
internal object StoreErrors {

    private const val CONSTRAINT_VALIDATION_FAILED = "Neo.ClientError.Schema.ConstraintValidationFailed"

    private const val SQLITE_EXCEPTION = "org.sqlite.SQLiteException"

    private const val SQLITE_ERROR = "SQLITE_ERROR"

    private val SQLITE_UNIQUENESS_CODES = setOf("SQLITE_CONSTRAINT_UNIQUE", "SQLITE_CONSTRAINT_PRIMARYKEY")

    /** The code of the driver exception a `SQLITE_ERROR` message quotes, right after its own text. */
    private val NESTED_SQLITE_CODE = Regex("""^\[SQLITE_ERROR][^(]*\(org\.sqlite\.SQLiteException: \[([A-Z_]+)]""")

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

    private fun isSqliteUniquenessFailure(error: Throwable): Boolean {
        val code = sqliteResultCode(error) ?: return false
        if (code in SQLITE_UNIQUENESS_CODES) return true
        if (code != SQLITE_ERROR) return false
        val inner = error.message?.let(NESTED_SQLITE_CODE::find)?.groupValues?.get(1) ?: return false
        return inner in SQLITE_UNIQUENESS_CODES
    }

    /** The extended result code on a SQLite driver exception, or null for any other exception. */
    private fun sqliteResultCode(error: Throwable): String? {
        if (error.javaClass.name != SQLITE_EXCEPTION) return null
        return runCatching { (error.javaClass.getMethod("getResultCode").invoke(error) as? Enum<*>)?.name }.getOrNull()
    }
}
