# Changelog

Notable changes to DICE. Each entry states its compatibility impact on consumers
(assistant/me and anything else tracking `0.2.0-SNAPSHOT`): **additive** (safe to
pick up), **behavioral** (same API, different runtime behavior — read the note),
or **breaking** (consumer change required; the entry links the migration notes
and the consumer PRs that deliver it).

## Unreleased

### Added

- Optional source revisions in the `dice` core provenance model, the first slice of DICE #64.
  `ProvenanceEntry` gains a sixth field, `sourceRevision`: an opaque, provider-defined string,
  non-blank when present, recording which version of a source a claim was read from.
  `SourceLocator.key()` is untouched, so one document read at two revisions is still one source
  identity — one `:Source` node, one "everything from this document" query.
  `SourceRevisionRef(sourceKey, sourceRevision)` is the value that names one version of one source
  for queries and carriage. Equality and dedup follow from the data class, so evidence at two
  revisions over the same span now survives a fold as two entries where it previously collapsed into
  one. Three context-scoped finders land on `PropositionRepository` — `findBySourceKey` (any
  revision), `findBySourceRevision` (exact), and `findRevisionlessBySourceLocator`. Each comes in a
  `ContextId`-typed form and a plain-String form; the typed form forwards to the String one, which is
  the override point, so a backend written in either language stays reachable from both. In
  `dice-storage`, `DrivinePropositionRepository` overrides all three and pushes them into Cypher
  (its context read carries no provenance, so the in-memory defaults would return nothing there),
  and `DerivedFrom` carries the revision as a `DERIVED_FROM` edge property. Two graph-write changes
  come with that, each fixing a way a revision was lost before any query ran. Edges now carry an
  `entryKey` identity and are written by MERGE on it, so one proposition citing one source at two
  revisions stores two rows: relationship-fragment mapping identifies an edge by its endpoints alone
  and collapsed them into one. And exact-text dedup now unions incoming evidence into the winner on
  both the in-process and cross-instance-race paths, so re-extracting the same sentence from a newer
  revision keeps that revision queryable instead of discarding it with the duplicate; an exact replay
  writes nothing. Provenance reads use raw Cypher for the same cardinality reason. One behavioural
  note for hosts that call `save` inside their own transaction: a cross-instance uniqueness race
  still propagates to the caller rather than being recovered internally, because the losing insert
  has already ended that transaction — recovery is the caller's to retry, and `save` recovers by
  itself only when it owns the transaction. `PropositionStore` gains
  the provenance-management operations `provenanceOf`, `addProvenance`, `setProvenance` and
  `clearProvenance`, which previously sat only on `PropositionRepository`, so evidence-sensitive
  callers can depend on the capability without probing for a richer type at runtime. Design note:
  [docs/design/source-revisions.md](docs/design/source-revisions.md).
  **Interim limitation.** Collapse records fold references as plain locator keys until the collector
  slice lands, so undoing a collapse that folded revisioned evidence leaves those entries on the
  survivor; evidence is retained, never wrongly deleted. Internally, `ProvenanceEvidenceKey` is the
  codec that will make those references exact — it is `internal` to the `dice` module, machinery
  behind fold and undo rather than API a consumer can call.
  **Compatibility: additive, with a scoped ABI boundary.** Source, JSON, and Java
  constructor-descriptor compatibility are claimed: existing Kotlin and Java call sites compile
  unchanged, provenance JSON written before this change loads and round-trips with a null revision,
  and `@JvmOverloads` preserves every concrete `ProvenanceEntry` constructor descriptor while the
  revision adds one on the end. The same holds for `dice-storage`'s `DerivedFrom`, which gains
  `@JvmOverloads` and takes `sourceRevision` and `entryKey` as trailing optional arguments. Full
  Kotlin synthetic constructor and `copy` ABI is **not** claimed for either type: adding a field to a
  data class changes the `copy` and `componentN` signatures and the synthetic `$default` constructor,
  so Kotlin code compiled against an earlier jar must be recompiled rather than swapped in. Stored
  graphs need no migration: a `DERIVED_FROM` edge with no `sourceRevision` reads back as a
  revisionless entry, and an edge with no `entryKey` is adopted in place the first time an exactly
  matching revisionless entry is written. The source queries carry no index hint, so they plan on a
  store that never adopted the optional `(contextId, text)` uniqueness constraint. Pipeline,
  collector, and REST behavior are unchanged; those arrive in the following Wave A slices.
