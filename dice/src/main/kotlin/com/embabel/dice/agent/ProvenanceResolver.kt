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
package com.embabel.dice.agent

/**
 * SPI for attaching provenance to memory results.
 *
 * Propositions are extracted from sources — emails, meetings,
 * documents — and the projector records that lineage as
 * `(Proposition)-[:GROUNDED_IN]->(source)` edges (the proposition's
 * `grounding` list holds the source ids). The human-readable
 * descriptor of a source (an email subject, a meeting title, a
 * document name) lives on that source row, which is a
 * per-deployment graph/store concern outside this open-source
 * module — [Memory] can't resolve it directly.
 *
 * So [Memory] accepts an optional implementation via
 * [Memory.withProvenance]. When wired, EVERY result the memory tool
 * returns is annotated with where it came from, so the calling LLM
 * can answer "why do you think X / cite that / name the email"
 * straight from a recall — no separate citation tool needed.
 *
 * This is NOT a retrieval hook: it does not influence what is
 * searched or returned, and it is not exposed as a tool parameter.
 * The memory tool's interface stays a single freeform `query`; this
 * only decorates the results. When no implementation is wired,
 * Memory returns bare proposition text. Backwards-compatible.
 */
fun interface ProvenanceResolver {

    /**
     * Resolve the human-readable sources backing the given
     * propositions.
     *
     * @param propositionIds the ids of the propositions Memory is
     *   about to return. Resolve in one batch — Memory calls this
     *   once per result set, not once per proposition.
     * @return map of proposition id → its source descriptors (e.g.
     *   email subjects, meeting titles), most relevant first.
     *   Propositions with no recorded source may be absent from the
     *   map or map to an empty list. Memory caps and truncates for
     *   display, so an implementation may return everything it has.
     */
    fun resolveSources(propositionIds: List<String>): Map<String, List<String>>
}
