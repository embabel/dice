# dice-bundle

Snapshots a set of propositions from one DICE context into a portable JSON file and reloads them
into another store. A bundle carries propositions together with their provenance, temporal metadata,
entity mentions, and free-form metadata, so everything needed to reason about a proposition travels
with it.

## Key types

| Type | Where | What it does |
|---|---|---|
| `KnowledgeBundle` | `KnowledgeBundle.kt` | The envelope. Holds a `contextId`, a `List<Proposition>`, a `createdAt` timestamp, optional `metadata` (free-form string map), optional `entities` (`List<EntitySnapshot>`) and `embeddings` (`List<EmbeddingEntry>`), and a `formatVersion` (currently `"2.0"`). Immutable data class. Use `KnowledgeBundle.from(contextId, propositions, ...)` to assemble one (throws `IllegalArgumentException` if any proposition belongs to a different context); there is a Java-friendly overload that accepts a plain string context ID. `@JsonIgnoreProperties(ignoreUnknown = true)` means a consumer on an older library version can still read bundles produced by a newer one. |
| `EntitySnapshotPort` / `EmbeddingPort` | `EntitySnapshotPort.kt`, `EmbeddingPort.kt` | Optional export/import SPIs a host app wires so a bundle can also carry entity data it owns (dice never owns entities itself) and stored embedding vectors (so re-import skips re-embedding at LLM cost). Both default to no-ops (`NoOpEntitySnapshotPort`, `NoOpEmbeddingPort`) via `KnowledgeBundleAssembler`'s constructor params, keeping both sections genuinely optional. See [knowledge-bundles.md](../docs/design/knowledge-bundles.md). |
| `KnowledgeBundleAssembler` | `KnowledgeBundleAssembler.kt` | Builds a `KnowledgeBundle` from a `PropositionRepository`: `forContext(contextId)` for one context, `allContexts()` for one bundle per distinct context in the store. |
| `KnowledgeBundleExporter` | `KnowledgeBundleExporter.kt` | SPI with three methods: `exportToString`, `exportToStream`, `exportToWriter`. All flush before returning but do not close the sink — that is the caller's responsibility. |
| `KnowledgeBundleImporter` | `KnowledgeBundleImporter.kt` | SPI with three methods: `importFromString`, `importFromStream`, `importFromReader`, each taking an optional `targetContextId` to re-scope every imported proposition into a different context than the bundle's own. Returns a `BundleImportOutcome`; never throws. The stream and reader overloads have default implementations that buffer to string and delegate to `importFromString`; the Jackson implementation overrides them to read directly from the source without double-buffering. |
| `ImportConflictPolicy` | `KnowledgeBundleImporter.kt` | Enum: `SKIP_EXISTING` (leave the store's copy alone) or `OVERWRITE` (replace it, best-effort/non-atomic). Default is `SKIP_EXISTING`. |
| `BundleImportOutcome` | `KnowledgeBundleImporter.kt` | Sealed interface with three variants: `Success` (holds the parsed bundle and an `ImportResult` with `imported`/`overwritten`/`skipped`/`rejected` counts plus per-proposition `PropositionImportNote` entries), `UnknownFormatVersion` (format gate fired, nothing written), `ParseFailure` (bad JSON or oversized input, nothing written). |
| `ImportResult` | `KnowledgeBundleImporter.kt` | Counts for a completed import: `imported`, `overwritten`, `skipped`, `rejected`, `total`, `notes`, and `overwriteFailures` (ids where an `OVERWRITE` save threw mid-flight — store state for those is undetermined and needs manual reconciliation). |
| `PropositionImportNote` | `KnowledgeBundleImporter.kt` | A per-proposition note for skipped or rejected items: proposition ID plus a human-readable reason string. |
| `JacksonKnowledgeBundleExporter` | `support/JacksonKnowledgeBundleExporter.kt` | Default exporter. Uses `jacksonObjectMapper().findAndRegisterModules()` so `Instant` fields serialise correctly. Thread-safe after construction. |
| `JacksonKnowledgeBundleImporter` | `support/JacksonKnowledgeBundleImporter.kt` | Default importer. Accepts a `supportedVersions` set (defaults to `{"2.0"}`), a `maxBundleBytes` cap (default 50 MB), and optional `entitySnapshotImporter` / `embeddingImporter`. Rejects oversized strings before deserialization. Configures Jackson with `FAIL_ON_UNKNOWN_PROPERTIES = false`. |

## What survives a round-trip

The round-trip test (`KnowledgeBundleRoundTripTest`) covers:

- Proposition core fields: `id`, `contextId`, `text`, `confidence`, `decay`, `status`, `contentRevised`
- Proposition metadata (arbitrary key/value pairs)
- `EntityMention` including the `hints` map (`String`, `Double` survive as-is; JSON integers in
  `Map<String, Any>` come back as `java.lang.Integer`, not `Long` — see test for details)
- `TemporalMetadata` (`observedAt`, `validFrom`, `validTo`)
- `ProvenanceEntry` with `UriLocator` (`uri`, `display`, `chunkId`, `startOffset`, `endOffset`)
- Bundle-level `metadata` map and `formatVersion`

## Dependencies

- **`dice` (core)** — required; provides `Proposition`, `PropositionStore`, `PropositionStatus`,
  `EntityMention`, `ProvenanceEntry`, and related types.
- **`embabel-agent-api`** and **`embabel-agent-rag-core`** — `provided`; supplied by the consuming
  application.
- **Jackson Databind + Kotlin module** — bundled; used by `JacksonKnowledgeBundleExporter` and
  `JacksonKnowledgeBundleImporter`.

## Typical usage

```kotlin
// Export
val bundle = KnowledgeBundle.from(contextId, propositionStore.findAll())
val json = JacksonKnowledgeBundleExporter().exportToString(bundle)
Files.writeString(outputPath, json)

// Import
val outcome = JacksonKnowledgeBundleImporter().importFromString(
    Files.readString(inputPath),
    targetStore,
    ImportConflictPolicy.SKIP_EXISTING,
)
when (outcome) {
    is BundleImportOutcome.Success -> println("imported ${outcome.result.imported}")
    is BundleImportOutcome.UnknownFormatVersion -> error("format mismatch: ${outcome.foundVersion}")
    is BundleImportOutcome.ParseFailure -> error("bad bundle: ${outcome.reason}")
}
```

## Gotchas

- **`createdAt` is part of structural equality.** Two `KnowledgeBundle` instances assembled from
  identical propositions at different times are not `==`. Pass an explicit `createdAt` when you
  need deterministic equality (e.g. caching or idempotency checks).
- **Integer vs Long in `hints`.** Jackson deserialises JSON integer values inside a
  `Map<String, Any>` as `java.lang.Integer` (not `Long`), regardless of how they were created.
  Cast through `Number.toInt()` / `Number.toLong()` rather than direct casting if portability matters.
- **50 MB size guard applies to all three import paths.** `importFromString` checks the UTF-8
  byte length up front; `importFromStream` and `importFromReader` wrap the source so the read is
  aborted once `maxBundleBytes` is exceeded (the reader path sums UTF-8 byte lengths, not
  characters). All three return `BundleImportOutcome.ParseFailure` when the limit is exceeded.
- **Format version gate is pre-write.** `UnknownFormatVersion` and `ParseFailure` are always
  clean no-ops — no propositions are written to the store before the format check passes.
- **No Spring auto-configuration.** The module ships no `@Configuration` class. Wire
  `JacksonKnowledgeBundleExporter` and `JacksonKnowledgeBundleImporter` as beans manually or
  construct them directly.
- **Deduplication at assembly time is the caller's responsibility; the importer dedups within a
  bundle.** `KnowledgeBundle.from` preserves duplicate ids exactly as supplied — it does not dedup.
  On import, though, a repeated id within the *same* bundle is only ever written once: the first
  occurrence is saved per the conflict policy and every later occurrence of that id is skipped with
  a note, so counts reflect distinct propositions, not raw entries.
- **Cross-context id collisions are refused, not resolved.** `PropositionStore` keys by id alone, not
  `(contextId, id)`. If an id already exists under a context other than the one being imported into
  (original or `targetContextId`-rescoped), the importer refuses to touch that row — counted as
  `rejected` — rather than guess whether it's safe to move or overwrite.
