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
package com.embabel.dice.projection.lineage

import com.embabel.dice.proposition.Proposition

/**
 * Strategy for reconciling a proposition's projection against artifacts that
 * already exist in a target backend, deciding whether to create a new artifact,
 * adopt an existing one, or align with it.
 *
 * Implementations encode the backend-specific matching strategy (for example
 * exact key, fuzzy name, or vector similarity); the contract itself is
 * backend-agnostic. This mirrors the entity-resolution contract defined by
 * [com.embabel.dice.common.EntityResolver], applied to projection targets rather
 * than entity mentions.
 */
interface Reconciler {

    /**
     * Decide how [proposition] should be projected to [target].
     *
     * @param proposition The proposition being projected
     * @param target The projection target (e.g. "graph")
     * @return whether to create new, adopt, or align with an existing artifact
     */
    fun reconcile(proposition: Proposition, target: String): ReconciliationDecision
}
