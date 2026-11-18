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

import org.drivine.query.CypherStatement
import org.drivine.query.QuerySpecification

/**
 * Starts a query from Cypher text, marked as Cypher.
 *
 * A statement with no language runs in the store's own one, so on a SQL store such as SQLite,
 * Postgres or Oracle Drivine would hand DICE's Cypher to the database as SQL. Marked as Cypher, it
 * runs as it is on a graph store and is compiled to SQL on the others.
 *
 * @param statement the Cypher to run
 * @return a query specification to bind parameters to and run
 */
internal fun cypherQuery(statement: String): QuerySpecification<Any> =
    QuerySpecification.withStatement(CypherStatement(statement))
