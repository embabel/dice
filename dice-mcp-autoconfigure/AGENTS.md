# `dice-mcp-autoconfigure` module — Agent Navigation Guide

Spring Boot wiring that exports [DiceMcpTools](../dice/src/main/kotlin/com/embabel/dice/mcp/DiceMcpTools.kt)
over embabel-agent's MCP server. No domain logic — just an `@AutoConfiguration` that assembles beans
from `dice`. `context_id` on every tool is a caller-supplied scope, not a credential — the
check lives on `DiceMcpTools` itself; authorization is the host MCP server's job.

## What's here

- **`DiceMcpAutoConfiguration`** — `DiceMcpTools` + named `diceMcpToolExport` (`McpToolExport`).
  Opt-in via `embabel.dice.mcp.enabled=true`. Requires `McpToolExport` on the classpath and a
  `PropositionRepository` bean. `afterName` waits for `DiceStorageAutoConfiguration` when that
  module is present so the store bean exists before `@ConditionalOnBean` is asked.
- **`DiceMcpProperties`** — `embabel.dice.mcp`: `enabled` (default false), `min-confidence`
  (default 0.5), `default-limit` (default 10).

## Property reference

| Property | Default | Meaning |
|---|---|---|
| `embabel.dice.mcp.enabled` | `false` | Master switch. Off means no beans. |
| `embabel.dice.mcp.min-confidence` | `0.5` | Minimum effective confidence for recall/list |
| `embabel.dice.mcp.default-limit` | `10` | Default result cap for recall/list. Must be `1..100` |

Every collaborator is `@ConditionalOnMissingBean`, so an app's own `DiceMcpTools` or
`diceMcpToolExport` bean wins.

## Dependencies

- `dice` — `DiceMcpTools` and the proposition store SPI.
- `embabel-agent-mcpserver` (optional) — `McpToolExport`. The host also adds
  `embabel-agent-starter-mcpserver`.
- `embabel-agent-api` (provided) — supplied at runtime by the consuming application.

## Gotchas

- MCP export is **opt-in**. Unlike the collector (`enabled` default true), this stays dark until
  `embabel.dice.mcp.enabled=true`.
- Without a `PropositionRepository` bean the auto-config class may load but it exports nothing.
- `default-limit` is bounded by `DiceMcpTools.MAX_LIMIT` (100), the ceiling the tools clamp every
  caller-supplied `limit` to. A larger default would bind and then be silently truncated on every
  call, so it fails startup instead.
- `afterName` is a string, not `after = [DiceStorageAutoConfiguration::class]`, so this module
  has no compile dependency on `dice-storage-autoconfigure`. The combined wiring test (test-scope
  only) is what proves the name is right and the store bean is visible.
- Discovery and graph tools are not on this path. They bake context in at construction; use
  `DiscoveryTools.asTools(...)` / `GraphQueryTools.asTools(...)` for in-process agents.
