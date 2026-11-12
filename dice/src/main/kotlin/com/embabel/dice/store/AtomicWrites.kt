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
package com.embabel.dice.store

import org.jetbrains.annotations.ApiStatus
import java.util.function.Supplier

/**
 * Runs several store writes as one unit. All of them are kept, or none are.
 *
 * ```java
 * Proposition saved = writes.atomically(() -> {
 *     Proposition proposition = store.save(claim);
 *     runs.record(run);
 *     return proposition;
 * });
 *
 * writes.runAtomically(() -> {
 *     store.save(claim);
 *     runs.record(run);
 * });
 * ```
 *
 * ```kotlin
 * val saved = writes.atomically {
 *     val proposition = store.save(claim)
 *     runs.record(run)
 *     proposition
 * }
 * ```
 *
 * An implementation keeps these rules:
 *
 * 1. The writes the work makes on the calling thread are kept when the work returns and undone
 *    when it throws anything. This covers writes through this store and through other DICE
 *    stores on the same database connection. A store on another connection is outside the unit.
 * 2. A store method that documents its own transaction, such as `touchAccessed`, stays outside
 *    the unit.
 * 3. When the work throws, none of its writes remain and the caller receives the same exception.
 * 4. A call made inside another call, or inside a transaction the application opened on the
 *    thread, joins it. Nothing is kept until the outermost one completes. A failure inside marks
 *    the outer transaction for rollback, so a caller that catches the exception and continues
 *    fails when the outer transaction commits.
 * 5. A read made inside the work sees the writes made inside it.
 * 6. The work runs once. The store does not retry it.
 *
 * A store implements this interface only when it can undo writes, and then lists
 * [StoreCapability.ATOMIC_WRITES]. A store that cannot undo writes as wired throws
 * [UnsupportedCapabilityException] without running the work.
 *
 * EXPERIMENTAL. This API may change before 1.0.
 */
@ApiStatus.Experimental
interface AtomicWrites {

    /**
     * Runs [work] and keeps its writes together or not at all.
     *
     * @param work The writes to make.
     * @return The value [work] returned.
     * @throws UnsupportedCapabilityException when the store cannot undo writes as wired. The work
     *   is not run.
     */
    fun <T> atomically(work: Supplier<T>): T

    /**
     * Runs [work], which returns nothing, and keeps its writes together or not at all.
     *
     * @param work The writes to make.
     * @throws UnsupportedCapabilityException when the store cannot undo writes as wired. The work
     *   is not run.
     */
    fun runAtomically(work: Runnable) {
        atomically<Unit> { work.run() }
    }
}
