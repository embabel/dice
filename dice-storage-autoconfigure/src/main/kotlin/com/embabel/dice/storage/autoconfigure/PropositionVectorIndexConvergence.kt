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

import com.embabel.common.ai.model.EmbeddingService
import org.slf4j.LoggerFactory
import org.drivine.manager.PersistenceManager
import org.drivine.schema.VectorIndexSpec

/**
 * Makes the proposition vector index once there is a model to make it at.
 *
 * THE INDEX IS CREATED AT THE EMBEDDING MODEL'S DIMENSION, so a host that starts without a model
 * cannot register one — [DiceStorageAutoConfiguration.propositionVectorIndexSchema] contributes an
 * empty catalog instead, rather than guessing a dimension and accepting writes a real model would
 * disagree with. That is still right.
 *
 * What was wrong was the sentence after it: "the catalog is rebuilt on the next boot, by which
 * time a model configured at first run is registered." That held while a host with no model
 * restarted after one was configured. The Embabel appliance stopped restarting — a provider key
 * now takes effect per call — so the next boot never comes, and the index was never made at all.
 * Observed: the model came alive and was used, and every read failed with "There is no such vector
 * schema index".
 *
 * A CATALOG CANNOT ANSWER THIS. `SchemaCatalog` holds materialised specs, and `SchemaManager`
 * snapshots the catalog beans when it is built, so nothing registered later is ever seen and
 * re-running `enforce()` applies the same empty catalog. The index has to be ensured directly.
 *
 * WHO CALLS IT IS THE HOST'S BUSINESS. Only the host knows when a model has appeared — it is the
 * one that took the key. Dice provides the capability and no trigger: no polling, no listener for
 * an event this library cannot see, and nothing that runs unasked.
 *
 * Idempotent, because `IndexManager.ensure` is: calling it when the index already exists is a
 * no-op, so a host may call it on every provider change without checking first.
 */
class PropositionVectorIndexConvergence internal constructor(
    private val persistenceManager: PersistenceManager,
    private val dimensionsOf: () -> Int?,
    private val spec: (Int) -> VectorIndexSpec,
) {

    private val logger = LoggerFactory.getLogger(javaClass)

    /**
     * Ensure the index, and say whether there is one now.
     *
     * False means there is still no embedding model — the ordinary answer on a host that has not
     * been given a key yet, and not an error. A host may call this hopefully.
     *
     * CREATES, AND NEVER RESHAPES. `IndexManager.ensure` is deliberately non-destructive: against
     * an index of a different shape it reports [org.drivine.schema.EnsureResult.Drift] and changes
     * nothing. That is the right default — recreating an index silently would strand every vector
     * already in it — and it is why this cannot follow a model CHANGE.
     *
     * It does not need to. `reembedAll` reconciles the index around its own rewrite, so the only
     * job left here is the one it was written for: making the index the FIRST time a key arrives,
     * when there is nothing stored to re-embed.
     */
    fun ensure(): Boolean {
        val dimensions = dimensionsOf() ?: return false
        return runCatching { persistenceManager.indexes.ensure(spec(dimensions)) }
            .onSuccess { logger.info("Proposition vector index converged at {} dimensions", dimensions) }
            .onFailure { logger.warn("Could not converge the proposition vector index: {}", it.message, it) }
            .isSuccess
    }


    companion object {

        /**
         * The dimension of the host's embedding model, or null when it has none.
         *
         * Read on every call rather than captured: a host whose model arrives after startup — the
         * whole reason this class exists — must be seen the next time somebody asks.
         *
         * Both failures mean the same thing and are treated the same: nothing resolves, or
         * something resolves and refuses to state a dimension because it is a placeholder waiting
         * for a key.
         */
        internal fun dimensionsFrom(resolve: () -> EmbeddingService): () -> Int? = {
            runCatching { resolve().dimensions }.getOrNull()
        }
    }
}
