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

import com.embabel.dice.store.StoreCapabilities
import org.jetbrains.annotations.ApiStatus

/**
 * The answers that differ between databases for the DICE stores in this module, one
 * implementation per database. The stores hold the queries, and a dialect states what the
 * database under them accepts and can do.
 *
 * The stores in this module do not take a dialect, so implementing this interface changes no
 * store's behaviour.
 *
 * ```java
 * final class ExampleDialect implements StoreDialect {
 *
 *     @Override
 *     public StoreCapabilities getCapabilities() {
 *         return StoreCapabilities.of(StoreCapability.ATOMIC_WRITES)
 *             .withVectorFiltering(VectorFiltering.AFTER_TOP_K);
 *     }
 *
 *     @Override
 *     public Set<DialectFeature> getFeatures() {
 *         return EnumSet.noneOf(DialectFeature.class);
 *     }
 *
 *     @Override
 *     public boolean isUniquenessViolation(Throwable failure) {
 *         Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
 *         for (Throwable cause = failure; cause != null && seen.add(cause); cause = cause.getCause()) {
 *             if (cause instanceof SQLException sql && "23505".equals(sql.getSQLState())) {
 *                 return true;
 *             }
 *         }
 *         return false;
 *     }
 * }
 * ```
 *
 * Three rules hold on every database, so a dialect has no member for them:
 *
 * 1. A store sends every hand-written graph query as a `CypherStatement`. A plain string carries
 *    no query language, and a database that also accepts SQL runs it as SQL.
 * 2. A store writes no NUL character in a string, and every reserved marker it stores uses
 *    characters all databases keep.
 * 3. A store relies only on labels that nodes carry. A label catalogue can also list a label that
 *    has a constraint and no nodes, and a store does not depend on that.
 *
 * EXPERIMENTAL. This API may change before 1.0.
 */
@ApiStatus.Experimental
interface StoreDialect {

    /**
     * The most that stores on this database can do. A store that uses the dialect publishes the
     * part that applies to it, and a store with no similarity search narrows it with
     * `withoutSimilaritySearch`.
     */
    val capabilities: StoreCapabilities

    /**
     * The query constructs this database accepts. A store that uses the dialect uses a construct
     * only when it is listed here. Each [DialectFeature] documents what such a store does without
     * it.
     */
    val features: Set<DialectFeature>

    /**
     * Whether a failed write was rejected by a uniqueness constraint, which means another writer
     * saved the same thing first.
     *
     * A store recovers from that by reading the row that won. An implementation checks the whole
     * cause chain, stops when a cause repeats, and uses the database's error code where one
     * exists.
     *
     * @param failure What the write threw.
     * @return True only for a uniqueness violation.
     */
    fun isUniquenessViolation(failure: Throwable): Boolean
}

/**
 * A query construct that some databases accept and others reject. Each entry documents what a
 * store does on a database without it.
 *
 * EXPERIMENTAL. This API may change before 1.0.
 */
@ApiStatus.Experimental
enum class DialectFeature {

    /**
     * A property key can be chosen at run time, as in `REMOVE p[key]`. Without it, a store changes
     * a set of keys by writing the whole property map.
     */
    DYNAMIC_PROPERTY_KEYS,

    /**
     * A label can be chosen at run time, as in `MATCH (n:$(label))`. Without it, a store runs one
     * statement per label.
     */
    DYNAMIC_LABELS,

    /**
     * A hand-written query can call the vector index, which lets one statement compare each
     * proposition with its neighbours. Without it, a store uses the typed nearest-neighbour call.
     */
    VECTOR_INDEX_CALLS,
}
