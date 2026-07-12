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
package com.embabel.dice.storage.autoconfigure

import org.drivine.connection.DatabaseType
import org.drivine.manager.PersistenceManager
import org.drivine.query.QuerySpecification
import org.drivine.query.grammar.CypherGrammar
import org.drivine.schema.ConstraintManager
import org.drivine.schema.IndexManager
import java.util.Optional

/**
 * A [PersistenceManager] test double that answers every read with an empty result and ignores every
 * write. Every member throws or no-ops except [query], which subclasses override to return canned
 * rows -- everything else here is just enough to satisfy the interface for tests that never
 * exercise it.
 */
open class NoOpPersistenceManager : PersistenceManager {
    override val database: String get() = "test"
    override val type: DatabaseType get() = DatabaseType.NEO4J
    override val grammar: CypherGrammar get() = throw UnsupportedOperationException("not needed by this test double")
    override val indexes: IndexManager get() = throw UnsupportedOperationException("not needed by this test double")
    override val constraints: ConstraintManager get() = throw UnsupportedOperationException("not needed by this test double")

    override fun <T : Any> query(spec: QuerySpecification<T>): List<T> = emptyList()
    override fun execute(spec: QuerySpecification<*>) {}
    override fun <T : Any> getOne(spec: QuerySpecification<T>): T =
        throw UnsupportedOperationException("not needed by this test double")

    override fun <T : Any> maybeGetOne(spec: QuerySpecification<T>): T? = null
    override fun <T : Any> optionalGetOne(spec: QuerySpecification<T>): Optional<T> = Optional.empty()
    override fun registerSubtype(baseClass: Class<*>, labels: List<String>, subClass: Class<*>) {}
}
