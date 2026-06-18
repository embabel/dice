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
package com.embabel.dice.proposition

/**
 * Open carrier for an LLM- or extractor-emitted hint that something
 * should be persisted. Concrete subtypes decide what — entities
 * ([com.embabel.dice.common.SuggestedEntity]), propositions
 * ([SuggestedProposition]), or app-domain facts shipped by
 * downstream modules (e.g. `SuggestedBiller`).
 *
 * Implements [Derivation] so every suggestion carries the same
 * epistemic shape as the things it eventually turns into
 * ([Proposition], [com.embabel.dice.projection.Projection]).
 *
 * **Open by design.** DICE owns the two base subtypes and ships
 * the default handlers; downstream modules add new subtypes and
 * register their own [SuggestionHandler]s. The drainer dispatches
 * by concrete class — unknown subtypes warn-and-drop so a pack
 * shipping a suggestion type the host doesn't know about can't
 * crash the pipeline.
 */
interface Suggestion : Derivation {

    /**
     * Stable identifier of the source that produced this suggestion —
     * e.g. `"email"`, `"calendar"`, `"osx"`, `"stripe-receipts"`,
     * `"propose_facts"`. Powers dedup-by-source, provenance display,
     * and source-scoped retraction.
     */
    val source: String
}

/**
 * Handler for one concrete [Suggestion] subtype. Multiple handlers
 * may register for the same type — for example a `SuggestedBiller`
 * could be processed by both an entity-promoter (writing the Org
 * to the KG) and a biller-index writer.
 *
 * **Why a runtime `type` field rather than a reified generic.**
 * Spring auto-wiring of `List<SuggestionHandler<*>>` erases the
 * type argument; the drainer needs a runtime key for `groupBy`
 * dispatch. Exposing `Class<T>` keeps the handler implementation
 * type-safe while letting the registry classify at the boundary.
 */
interface SuggestionHandler<T : Suggestion> {

    /** The concrete subtype this handler accepts. */
    val type: Class<T>

    /**
     * Apply [suggestions] in bulk. Implementations may batch the
     * write to the underlying store (Drivine, DICE repo, app-domain
     * persistence) — the drainer hands the full group at once.
     *
     * @param contextId stable per-user / per-workspace partition id
     *   the suggestions belong to. Mirrors the [ContextId]
     *   partitioning used elsewhere in DICE.
     */
    fun apply(contextId: com.embabel.agent.core.ContextId, suggestions: List<T>)
}
