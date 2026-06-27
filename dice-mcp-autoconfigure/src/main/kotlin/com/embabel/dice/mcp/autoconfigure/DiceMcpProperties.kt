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
package com.embabel.dice.mcp.autoconfigure

import com.embabel.dice.mcp.DiceMcpProfile
import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * Configuration for exporting DICE as MCP tools.
 *
 * Requires `embabel-agent-starter-mcpserver` (or `embabel-agent-mcpserver`) on the classpath
 * and `embabel.dice.mcp.enabled=true`.
 */
@ConfigurationProperties(prefix = "embabel.dice.mcp")
data class DiceMcpProperties(
    /** Master switch. Default false so MCP export is opt-in. */
    val enabled: Boolean = false,
    /** Tool surface: [DiceMcpProfile.CORE] (4 tools) or [DiceMcpProfile.EXTENDED]. */
    val profile: DiceMcpProfile = DiceMcpProfile.CORE,
    /** Minimum effective confidence for recall/list tools (0.0–1.0). */
    val minConfidence: Double = 0.5,
    /** Default result limit for recall/list tools. */
    val defaultLimit: Int = 10,
) {
    init {
        require(minConfidence in 0.0..1.0) { "embabel.dice.mcp.min-confidence must be between 0.0 and 1.0" }
        require(defaultLimit > 0) { "embabel.dice.mcp.default-limit must be positive" }
    }
}
