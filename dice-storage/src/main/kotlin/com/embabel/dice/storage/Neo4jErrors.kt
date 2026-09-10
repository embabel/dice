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
 * Tells a Neo4j uniqueness constraint violation apart from every other kind of failure, by the
 * driver's own status code.
 *
 * Every [Neo4jException] carries a `code()` straight from the server, and a uniqueness constraint
 * violation always carries the same one: [CONSTRAINT_VALIDATION_FAILED]. That code is a contract
 * the server publishes and the driver just forwards, so it holds across driver versions in a way
 * an exception message never can, since a message is free text a future release can reword without
 * warning. Drivine does not translate driver exceptions into Spring's `DataAccessException`
 * hierarchy, so there is no Spring type to catch here either. This code check is what both stores
 * in this module use to tell "someone else already wrote the same thing" apart from a real failure.
 */
internal object Neo4jErrors {

    private const val CONSTRAINT_VALIDATION_FAILED = "Neo.ClientError.Schema.ConstraintValidationFailed"

    /**
     * Walks the cause chain looking for a [Neo4jException] carrying [CONSTRAINT_VALIDATION_FAILED].
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
            current = current.cause
        }
        return false
    }
}
