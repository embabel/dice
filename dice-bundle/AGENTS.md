# dice-bundle

Snapshots a set of propositions from one DICE context into a portable JSON file and reloads them
into another store. A bundle carries propositions together with their provenance, temporal metadata,
entity mentions, and free-form metadata, so everything needed to reason about a proposition travels
with it.

## Key types

| Type | Where | What it does |
|---|---|---|
| `KnowledgeBundle` | `KnowledgeBundle.kt` | The envelope. Holds a `contextId`, a `List<Proposition>`, a `createdAt` timestamp, optional `metadata` (free-form string map), and a `formatVersion` (currently `"1.0"`). Immutable data class. Use `KnowledgeBundle.from(contextId, propositions)` to assemble one; there is a Java-friendly overload that accepts a plain string context ID. `@JsonIgnoreProperties(ignoreUnknown = true)` means a consumer on an older library version can still read bundles produced by a newer one. |
| `KnowledgeBundleExporter` | `KnowledgeBundleExporter.kt` | SPI with three methods: `exportToString`, `exportToStream`, `exportToWriter`. All flush before returning but do not close the sink — that is the caller's responsibility. |
| `KnowledgeBundleImporter` | `KnowledgeBundleImporter.kt` | SPI with three methods: `importFromString`, `importFromStream`, `importFromReader`. Returns a `BundleImportOutcome`; never throws. The stream and reader overloads have default implementations that buffer to string and delegate to `importFromString`; the Jackson implementation overrides them to read directly from the source without double-buffering. |
| `ImportConflictPolicy` | `KnowledgeBundleImporter.kt` | Enum: `SKIP_EXISTING` (leave the store's copy alone) or `OVERWRITE` (replace it). Default is `SKIP_EXISTING`. |
| `BundleImportOutcome` | `KnowledgeBundleImporter.kt` | Sealed interface with three variants: `Success` (holds the parsed bundle and an `ImportResult` with `imported`/`skipped`/`rejected` counts plus per-proposition `PropositionImportNote` entries), `UnknownFormatVersion` (format gate fired, nothing written), `ParseFailure` (bad JSON or oversized input, nothing written). |
| `ImportResult` | `KnowledgeBundleImporter.kt` | Counts for a completed import: `imported`, `skipped`, `rejected`, `total`, and `notes`. |
| `PropositionImportNote` | `KnowledgeBundleImporter.kt` | A per-proposition note for skipped or rejected items: proposition ID plus a human-readable reason string. |
| `JacksonKnowledgeBundleExporter` | `support/JacksonKnowledgeBundleExporter.kt` | Default exporter. Uses `jacksonObjectMapper().findAndRegisterModules()` so `Instant` fields serialise correctly. Thread-safe after construction. |
| `JacksonKnowledgeBundleImporter` | `support/JacksonKnowledgeBundleImporter.kt` | Default importer. Accepts a `supportedVersions` set (defaults to `{"1.0"}`) and a `maxBundleBytes` cap (default 50 MB). Rejects oversized strings before deserialization. Configures Jackson with `FAIL_ON_UNKNOWN_PROPERTIES = false`. |

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
- **50 MB string guard applies only to `importFromString`.** The `importFromStream` and
  `importFromReader` paths in `JacksonKnowledgeBundleImporter` stream directly through Jackson
  without the byte-length check. Apply your own size limit at the transport layer when reading
  from untrusted streams.
- **Format version gate is pre-write.** `UnknownFormatVersion` and `ParseFailure` are always
  clean no-ops — no propositions are written to the store before the format check passes.
- **No Spring auto-configuration.** The module ships no `@Configuration` class. Wire
  `JacksonKnowledgeBundleExporter` and `JacksonKnowledgeBundleImporter` as beans manually or
  construct them directly.
- **Deduplication is the caller's responsibility.** The bundle preserves duplicates exactly as
  supplied. The importer's conflict policy handles ID collisions with existing store entries,
  but duplicates within the bundle itself are saved as-is.
