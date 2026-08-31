# Changelog

Notable changes to DICE. Each entry states its compatibility impact on consumers
(anything tracking `0.2.0-SNAPSHOT`): **additive** (safe to
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
  one. Three context-scoped finders arrive on a new opt-in capability interface,
  `SourceRevisionQueryCapable` — `findBySourceKey` (any revision), `findBySourceRevision` (exact),
  and `findRevisionlessBySourceLocator`. Each comes in a `ContextId`-typed form and a plain-String
  form; the typed form forwards to the String one, which is the implementation point, so a backend
  written in either language stays reachable from both. The String forms are abstract, so a backend
  that implements the interface has promised to answer for its own storage, and a backend that
  cannot answer is simply absent from the type: a caller probes with `as?` and handles that absence
  as its own case. `PropositionRepository` carries none of this surface. A shared default body
  written over an ordinary context read would have returned an empty list on any backend that stores
  evidence without projecting it, and an empty list already carries a meaning here — nothing in this
  context cites that source — so an unsupported operation would have read as a false negative.
  `ProvenanceScanningSourceRevisionQueries` supplies the in-memory scan for the stores whose reads do
  carry every entry (`InMemoryPropositionRepository`, `JsonFilePropositionRepository`).
  `EventEmittingPropositionRepository` carries the capability type and forwards to its delegate,
  reporting `supportsSourceRevisionQueries = false` and throwing with the delegate named when the
  delegate it was handed cannot answer. In `dice-storage`, `DrivinePropositionRepository` implements
  the capability and pushes all three predicates into Cypher, because its context read carries no
  provenance at all, and `DerivedFrom` carries the revision as a `DERIVED_FROM` edge property. Two graph-write changes
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
  survivor; evidence is retained, never wrongly deleted. `ProvenanceEvidenceKey` is the codec that
  will make those references exact.
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
- Length ceilings on the externally supplied strings that become stored identity, in
  `SourceIdentityBounds`: `MAX_SOURCE_KEY_LENGTH` (2048) and `MAX_SOURCE_REVISION_LENGTH` (1024).
  Both are checked while `ProvenanceEntry` and `SourceRevisionRef` are being constructed, which sits
  upstream of every hash and every indexed write, so a runaway value is refused with an
  `IllegalArgumentException` naming the limit it broke, before any store is touched. The numbers are
  roomy on purpose: 2048 is the practical ceiling browsers and proxies settled on for a URL, and 1024
  is the longest real revision token we know of, an S3 object version id.
  **Compatibility: behavioral.** A caller offering a source key or revision longer than the limit now
  gets a rejection where it previously got an oversized index entry. Nothing a real connector emits
  comes close to either number.

### Fixed

- `:Source.display` in the graph projection is write-once. A `:Source` node is global — one locator
  key is one node across every context that cites it — and `display` used to be refreshed on every
  write, so whichever writer ran last owned the label every other context read. It is now set on
  create only. Identity is unaffected: `display` has never participated in `SourceLocator.key()`, and
  each writer's evidence still lands on its own `DERIVED_FROM` edge.
  **Compatibility: behavioral, `dice-storage` only.** An existing `:Source` node keeps the label it
  currently has, and a later write leaves that label alone.

- One evidence-key codec across the `dice` core and the graph, plus the storage proofs that go with
  it (second slice of DICE #64). `com.embabel.dice.provenance.ProvenanceEvidenceKey` is now public:
  `dice-storage` keys each `DERIVED_FROM` edge by the string it mints, so a stored graph row and a
  recorded fold reference are the same string for the same piece of evidence. Storage previously
  carried its own copy of the encoding; the two framed the same fields in the same order and differed
  only in the version prefix, and each being `internal` to its own module meant nothing could catch a
  drift between them. A golden-literal test now pins the `v1` bytes, so the format cannot move under
  its own version label. Source-identity collisions are rejected in two places: under the `MERGE` that
  upserts the shared `:Source` node, which is what separates two writers introducing one colliding key
  at the same time, and in a preflight over the whole batch before the first write, which keeps a
  rejected write from changing anything and also rejects a single write carrying two structurally
  different locators under one key. Evidence for behavior that was previously asserted only in prose:
  the source finders are EXPLAINed as the repository binds them, and seek the `contextId` range index
  before expanding provenance where that index exists, falling back to a label scan and still
  answering where it does not; `delete` takes every edge of a proposition citing one source at
  several revisions and prunes only the sources left with no citations; a repeated or concurrent
  write of one revision stays one relationship; a pre-`entryKey` edge is claimed only by an exactly
  matching revisionless entry; and the source-identity guard rejects two structurally different
  locators sharing one key, checked two ways — a preflight over one write's own batch, and a check of
  whatever the `:Source` node already holds. `ConnectorRef` escaping its connector id
  (`fix(provenance): escape connector ids so ConnectorRef keys are injective`, main) closed the
  *batch-internal* half's trigger: the pair of tuples `dice-storage`'s tests used to exercise it with
  now render distinct keys, so that half's coverage now pins distinct sources and coexisting evidence
  instead of a rejection. The *stored-vs-incoming* half is not dead: a store that predates that fix
  can still hold a `:Source` node keyed the old, ambiguous way, and a current write naming a
  structurally different locator that renders the same key is rejected against it —
  `dice-storage`'s coverage now seeds exactly that legacy shape and pins the rejection. Design note:
  [docs/design/source-revisions.md](docs/design/source-revisions.md).
  **Compatibility: additive.** `ProvenanceEvidenceKey` widens from `internal` to public, which adds
  API rather than removing it; the format it encodes is unchanged and already carried a `v1` version
  prefix, and a reader meeting a version it does not know matches nothing rather than guessing. A
  stored `entryKey` now carries that prefix, where the storage-local copy of the encoding omitted it.
  Released graphs are unaffected — the `entryKey` property itself is part of this same unreleased
  block, so no released DICE ever wrote one, and an edge with no `entryKey` is still adopted in place
  by an exactly matching revisionless entry. A graph written by a build of the previous entry in this
  block holds prefix-less keys that this build does not recognise, and re-saving that evidence adds a
  second edge for it; clearing such a development store is the whole of the fix.
