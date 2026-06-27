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
package com.embabel.dice.mcp

/**
 * Tool surface exposed over MCP.
 *
 * Mirrors the core/extended split used by other memory MCP servers (e.g. neo4j-agent-memory):
 * a small read/write cycle for everyday recall, plus extraction and entity assertion when the
 * host application wires the heavier pipelines.
 *
 * ## Why `context_id` on every tool
 *
 * Unlike the in-process [com.embabel.dice.agent.Memory] tool — which bakes [com.embabel.agent.core.ContextId]
 * in at construction time — MCP clients are stateless and may serve many sessions. Requiring an
 * explicit `context_id` on each call prevents cross-tenant / cross-session knowledge leakage,
 * the same motivation as context-scoped [com.embabel.dice.incremental.ChunkHistoryStore] keys
 * introduced in #6.
 *
 * ## Profiles
 *
 * - [CORE] — recall, list, store, get. Safe to expose when only a [com.embabel.dice.proposition.PropositionRepository] exists.
 * - [EXTENDED] — adds [DiceMcpTools.EXTRACT] and [DiceMcpTools.ASSERT_ENTITIES] only when the
 *   corresponding Spring beans are present, so MCP export never advertises tools that would fail at runtime.
 */
enum class DiceMcpProfile {
    /** Essential recall and persistence: search, list, store, get-by-id. */
    CORE,

    /** [CORE] plus text extraction and entity assertion when wired. */
    EXTENDED,
    ;

    /**
     * Tool names to export for this profile.
     *
     * Extended optional tools are included only when their backing services are configured,
     * so the MCP `tools/list` response matches what can actually be invoked.
     */
    fun toolNames(
        pipelineAvailable: Boolean = false,
        entityResolutionAvailable: Boolean = false,
    ): Set<String> {
        val names = linkedSetOf(
            DiceMcpTools.RECALL,
            DiceMcpTools.LIST,
            DiceMcpTools.STORE,
            DiceMcpTools.GET,
        )
        if (this == EXTENDED) {
            if (pipelineAvailable) names += DiceMcpTools.EXTRACT
            if (entityResolutionAvailable) names += DiceMcpTools.ASSERT_ENTITIES
        }
        return names
    }
}
