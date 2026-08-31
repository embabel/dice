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

- Precise undo of a collapse that folded revisioned evidence (third slice of DICE #64).
  `RetiredProposition` gains `foldedProvenanceEvidenceKeys`, one `ProvenanceEvidenceKey` per entry a
  fold actually added to the survivor, and `MultiSignalCollectorStrategy` records it. Recording by
  locator key alone was wrong in two ways, both now fixed: a loser citing `r2` of a document the
  survivor already cited at `r1` shares that survivor's locator key, so the fold recorded nothing and
  `r2` stayed on the survivor after an undo; and a bare locator key matches revisionless evidence
  only, so a recorded ref could not reach a revisioned entry either. `undoSingleCollapse` now
  subtracts evidence through `subtractProvenance`, which names the refs to
  remove — the ordinary `save` on the graph backend appends provenance and deletes no edge, so the
  folded rows used to outlive the undo, and the authoritative replace that would remove them has to
  name what *stays*, silently discarding evidence a concurrent extraction added since the read.
  `DrivinePropositionRepository` performs it with one statement that deletes the named edges and
  prunes only their orphaned sources; the entry below moves the operation onto its own capability
  interface and states its atomicity. Undo also reads both the survivor and the retired proposition
  before writing anything, so a collapse whose participants have since been deleted leaves nothing
  half-written. It also authorizes on two conditions rather than trusting the trace: the collapse
  must be one the collector applied *into this survivor*, and it must still be in force.

  The first condition needed a fact nothing recorded, so `CollectorRecord` gains `mergedIntoId` —
  the survivor a sweep actually folded a proposition into, written by `DefaultCollectorRunner` only
  after the merge is saved. Three different outcomes used to be indistinguishable from an applied
  merge: a `StatusTransitionSweepPolicy` retirement, the fallback retirement the runner performs
  when a merge target has vanished or is no longer active, and a member marked as a duplicate of
  several survivors by different strategies, where only one merge ran. All three now leave
  `mergedIntoId` null or naming the real target. The field is written identically on every record a
  run writes for a proposition, which matters because `DrivineCollectorRecordStore` MERGEs on
  (`propositionId`, `runId`) and keeps one row per member per run while the in-memory store keeps
  one per mark — reading a merge target off a mark gave different answers on the two stores.
  `undoSingleCollapse` takes a `CollectorRecordStore` and requires a non-dry
  run and a record naming this survivor as the applied target.

  The second condition is that the undo has not already run, which needed a second new field:
  `CollectorRecord.undoneAt`, stamped by `undoSingleCollapse` when it finishes. Audit records never
  expire, so without it a member re-retired later by anything at all — a decay sweep, a second
  collector run — would re-arm the original undo and let it subtract that run's evidence a second
  time, taking evidence the survivor had since re-gained and clobbering the newer retirement. A
  store keyed by (proposition, run) updates its row in place; one that appends leaves the original
  beside the stamped copy, and any stamped record for the pair settles it. Replaying a collector
  outcome no longer erases the stamp — `DrivineCollectorRecordStore` writes it through `coalesce`,
  and `CollectorRecordStore.record` states that requirement for other implementations. The stamp is
  written after the survivor's evidence comes off but before the member's status is restored, so
  every interruption of an undo is recoverable: before the stamp a retry re-runs the whole thing
  (the subtraction is recomputed from current evidence, so it removes nothing twice), and after it a
  retry completes the restore alone and touches no evidence. Recognising that half-finished state
  needs the record to say where the collapse left the member and no other run to have *written* the
  member since — counted over `TRANSITIONED` and `HARD_DELETED` records from non-dry runs, since a
  `SKIPPED` record states a run left it alone and a dry run changes nothing. The member's own status is checked as
  well, and is all a caller without records has. It costs one deliberate false
  refusal — a member whose undo has not run and which has since revived to its prior status reads as
  never retired, and refusing leaves evidence alone where accepting could delete it silently. A
  sibling's folded refs are held on the survivor only until that sibling's own undo has run — read
  from its `undoneAt` when records are supplied, so a sibling undone and then retired again by a
  later run no longer reads as still participating — and undoing every member of a shared fold
  returns the survivor to its pre-collapse evidence instead of pinning the shared entry forever.
  The survivor's evidence subtraction now completes before the save that a decorator such as
  `EventEmittingPropositionRepository` publishes from, so a listener sees the post-undo state.
  `DrivineCollectorTraceStore` persists and reads the new field. Tests fold a
  revisioned loser into a survivor, undo, and assert the survivor's evidence and its `DERIVED_FROM`
  edge count are exactly what they were before the fold, in memory and against Neo4j. Design note:
  [docs/design/source-revisions.md](docs/design/source-revisions.md).
  **Compatibility: additive, with the same scoped ABI boundary as the first slice.** `@JvmOverloads`
  on `RetiredProposition` preserves the five-argument Java constructor descriptor and adds one on the
  end; full Kotlin synthetic constructor and `copy` ABI is not claimed, since adding a field to a
  data class changes `copy`/`componentN` and the synthetic `$default` constructor. Stored traces need
  no migration: a `:CollectorRetired` row written before this change has no
  `foldedProvenanceEvidenceKeys` property, reads back with an empty list, and undoes at locator
  granularity exactly as it did. Evidence keys are left out of the JSON view of a trace, so existing
  trace JSON is unchanged and a trace that goes through JSON comes back undoing at locator
  granularity. Three behavioural notes for hosts: undo now issues a `subtractProvenance` call *before* the
  survivor's `save`, so a custom `PropositionStore` sees that call — and gets the documented
  read-modify-write default unless it overrides — while the event a save decorator publishes
  describes the survivor's final state; undo writes to the record
  store when one is supplied, re-recording the run's row for the member with `undoneAt` set between
  the survivor's writes and the member's restore; and an undo returns null having written nothing
  when a survivor or retired member no longer exists, when the collapse cannot be shown to have been
  applied into this survivor, when that collapse has already been undone, or when the member is not
  currently retired — where it could previously have saved the reduced survivor first. A survivor
  deleted between the undo's read of it and the subtraction is caught at the subtraction, which
  answers null for a proposition the store no longer has: the undo ends there rather than saving
  back the copy it read, which would recreate the deleted proposition with the folded evidence still
  on it. The member stays retired, no stamp is written, and the null return says truthfully that no
  restore happened. How much of the run that covers depends on the store: the graph backend's
  override answers from a read taken after its one-statement delete, so its null is exact and only
  the gap before the survivor's `save` stays open, while the read-modify-write default — on a store
  inheriting `setProvenance`'s default too — can recreate the survivor inside its own subtraction
  and can hand back a proposition its first read saw. A
  deletion later than the store's last look is not detected, and the survivor is recreated by an
  upserting `save`; the design note records the residual per path and what closing it would take. An undo
  interrupted after its stamp resumes by restoring the member only, without re-deriving evidence.
  `undoSingleCollapse` gains a `CollectorRecordStore` parameter; the entry below makes it required
  and reshapes the parameter list, so read that entry for the call-site impact.
  `CollectorRecord` gains trailing optional `mergedIntoId` and
  `undoneAt` fields, keeping every existing constructor and `of(...)` descriptor through the
  `@JvmOverloads` already on both; audit rows written before this change carry neither property and
  read back as no merge and no undo, which is the right answer for a graph that predates them. Undo
  now reads each sibling of a collapse, so a decision with many retired members
  costs one extra read per
  member.

- Collapse undo fails closed (fourth slice of DICE #64). Three ways it could previously act on
  something the caller had no standing to reverse are shut, and the store operation it depends on
  becomes an explicit promise.

  **The undo names its context.** `undoSingleCollapse` took a survivor id and a retired id, so ids
  found anywhere at all could reverse a collapse in a context the caller has no business writing to,
  and every deployment that cared had to write its own ownership check in front of the call — the
  assistant does exactly that today. The parameters are now a `CollapseUndoCommand(contextId,
  survivorId, retiredId)`, and both propositions must live in that context. One that does not throws
  `CollapseUndoContextMismatchException`, which carries the commanded context, the offending
  proposition and the context that really owns it, and is thrown before either proposition is
  written, so the other context is left as it was.

  **A record store is required, and a dry-run record never authorizes.** Passing no
  `CollectorRecordStore` used to skip the "did the collector really apply this merge" question
  entirely and fall back on the member's status, which a later unrelated retirement satisfies just
  as well — a decay sweep moving the member ACTIVE to STALE after a dry-run preview was enough to
  arm an undo that then stripped a revision the survivor held for its own reasons. A null store now
  throws `CollapseUndoConfigurationException` naming the missing store, before any read. The
  authorizing record has to be live: `CollectorRun.dryRun` on the run header is how a preview is
  marked, and a dry-run header refuses whatever its records say. Three tests share one world state
  and vary only the audit trail — no store refuses, a dry-run record refuses, a live record
  proceeds — and each refusal asserts the survivor's evidence, the member's status and the absence
  of an `undoneAt` stamp.

  **Evidence subtraction becomes an atomic capability.** `PropositionStore.subtractProvenance` and
  its read-modify-write default are removed, replaced by `ProvenanceSubtractionCapable`, an opt-in
  interface alongside `SourceRevisionQueryCapable`. Its contract states what a shared default body
  could never deliver: the read of the current entries and the write of what survives land as one
  step, evidence another writer adds while a subtraction is in flight survives it, and subtracting
  the last entry from a proposition somebody already deleted answers null and writes nothing.
  `supportsProvenanceSubtraction` reports the runtime truth for a decorator that forwards.
  `InMemoryPropositionRepository` implements it over `ConcurrentHashMap.compute`, which holds the
  key for the whole operation, and gets an `addProvenance` override on the same primitive so the
  pair is race-free; `EventEmittingPropositionRepository` carries the capability type and forwards
  to its delegate, reporting false when the delegate it was handed lacks it. Undo requires the
  capability and refuses with `CollapseUndoConfigurationException` when the store cannot answer.
  `ProvenanceSubtractionAtomicityTest` races a subtraction against an addition on one proposition
  over 200 rounds with real threads and a start latch, and asserts both effects survive; the
  read-modify-write shape it replaced loses the addition on the first round.

  **Compatibility: one deprecation and one source-level break**, both with a mechanical migration.

  *`undoSingleCollapse`'s parameter list.* The four-argument form stays, marked `@Deprecated`, with
  the body it shipped with: it checks neither context ownership nor the audit records, so a caller
  still on it keeps the behavior it had and gets a compiler warning naming the guarded form. It is
  removed in the next minor release. Migration:

  ```kotlin
  // before
  undoSingleCollapse(traceQuery, propositionStore, survivorId, retiredId)
  // after
  undoSingleCollapse(
      command = CollapseUndoCommand(contextId, survivorId, retiredId),
      traceQuery = traceQuery,
      propositions = propositionStore,   // must be ProvenanceSubtractionCapable
      collectorRecords = collectorRecordStore,
  )
  ```

  A caller with no `ContextId` to hand has to obtain one, which is the point: without it the call
  had no way to say whose collapse it was reversing. A caller with no `CollectorRecordStore` has to
  wire one; passing null compiles and throws, so the gap surfaces immediately. The known downstream
  caller already resolves the user's context and checks both propositions against it before calling
  in — the migration hands the SPI the work that service was doing by hand, and its own checks can
  stay or go.

  *`PropositionStore.subtractProvenance`.* Removed along with its read-modify-write default. A store
  that declared `override fun subtractProvenance` stops compiling until it declares
  `ProvenanceSubtractionCapable` and drops the `override`. `DrivinePropositionRepository` is adapted
  in this change: its body already deleted the named `DERIVED_FROM` edges in one statement and read
  once afterwards, so it satisfied the new contract before the contract existed, and only the
  supertype list moved. A store that never mentioned the operation compiles unchanged, and gains
  nothing at runtime: absent the capability, undo refuses, where a racy fallback would have written
  silently.

  No stored data changes, and nothing in the provenance, trace or audit record shapes moves. Design
  note: [docs/design/source-revisions.md](docs/design/source-revisions.md).
- Source revisions reach the extraction entry points, the pipeline stamp, and the REST surface
  (fourth and last Wave A slice of DICE #64). `SourceAnalysisContext` gains `sourceRevision`, and it
  is the only channel a revision travels on: every entry point builds a context, and the context's
  `init` requires a `sourceLocator` whenever a revision is set and requires the revision's
  `sourceKey` to equal that locator's key. One check therefore covers every caller, and the
  content-hash locator the pipeline falls back to when no locator was supplied can never acquire a
  revision. `PropositionPipeline` stamps the revision onto each `ProvenanceEntry` it writes.
  `IncrementalPropositionExtraction` takes a locator and a revision on an `ExtractionRequest`,
  described in the extraction-profiles entry below: `rememberText` and `rememberFile` each gain one
  overload taking it, and the two signatures they already had are untouched. Supplying a revision
  asserts that the locator's revision covers the whole text or the whole file as extracted — DICE
  cannot derive that from an untyped `sourceId` or from `additionalGrounding`, and the KDoc says
  so. The request refuses a revision with no locator, or one naming a different source key, while
  it is being built, so a mismatched pair never reaches an entry point at all. `SourceAnalysisRequestEvent` gains `sourceLocator()` and `sourceRevision()`, both open and both
  returning null, and `ConversationAnalysisRequestEvent` gains a constructor that takes a locator and
  an optional revision; the listener feeds both into the same `buildContext` call the direct entry
  points use, so the async path grounds propositions identically and a test captures the context the
  pipeline was handed to prove it. On REST, `POST /extract` takes optional `sourceLocator`
  (`{kind, value, connectorId?, display?}`) and `sourceRevision` fields and `POST /extract/file`
  takes the same two as multipart parts. Combinations that would quietly mean something else are
  rejected with 400 before the pipeline runs and before Tika reads an upload: a revision with no
  locator, an unknown `kind`, a `connectorId` on a `uri`/`file`/`content` locator, a `connector`
  locator without one, and a blank `value`. A colon inside a `connectorId` is accepted, because
  `ConnectorRef` escapes its own connector id when it renders a key: `gmail:eu-west` and `gmail`
  stay distinguishable, and a region-qualified connector id round-trips through `key()` unharmed.
  The same 400 also covers a source key or a source revision longer than the `SourceIdentityBounds`
  ceilings (2048 and 1024), both measured while the request is being read, so an over-long value is
  answered at the edge where a caller can act on it. Every one of these refusals now returns a body
  saying why, a new `ExtractErrorResponse`; the compatibility note below states what that changes for
  a caller.
  A revisioned request also derives each chunk's id from the source key, the revision, the
  chunk's ordinal, and its text, so re-posting the same revision lands on the same grounding rows
  instead of accumulating a fresh set per replay, while a different revision of the same source stays
  separately traceable. Those ids are context-scoped: the context id is part of the hashed identity,
  because a chunk id is what grounding is looked up by and `findByGrounding` is not context-scoped, so
  without it two contexts ingesting one document at one revision would mint one id and a grounding
  lookup in either could reach the other's propositions. They also assume a stable chunker: the chunk
  text and ordinal are in the identity, so re-posting one revision after a chunker configuration
  change re-mints the ids and grounds onto fresh rows beside the old ones. `POST /extract` answers
  with the propositions the store holds once the writes have run. `save` is the authority on what is
  there: exact-text dedup hands back an existing canonical row under its own id, and a merge or a
  reinforcement writes the revised proposition while the freshly extracted one is never stored at
  all. Both cases used to leave the response naming an id no read could resolve. Every proposition
  id in an extract response now resolves through `findById`, and a test asserts exactly that over the
  dedup, merge, contradiction and reinforcement paths.
  `ProvenanceEntryDto` gains `sourceRevision`, and the discovery `/why`
  response grows a `provenance` array of primitive-only `DiscoveryProvenanceDto` values built from
  the lineage's own entries and sorted by evidence key. The sort is what makes the field
  deterministic: provenance is read as raw Cypher over `DERIVED_FROM` edges and comes back in planner
  order, so without it one proposition could serialize its evidence differently from one read to the
  next. A pinned client jar compiled against `main` before any of this
  landed now runs as a test, so the compatibility boundary below is measured rather than asserted.
  Design note: [docs/design/source-revisions.md](docs/design/source-revisions.md).
  **Compatibility: additive, with the same scoped ABI boundary as the earlier slices.** Source, JSON,
  and Java constructor-descriptor compatibility are claimed. `@JvmOverloads` on `SourceAnalysisContext`
  preserves every concrete Java constructor descriptor and adds one on the end, and the same holds for
  `ExtractRequest`, `ProvenanceEntryDto`, and `LineageDto`; `rememberText` and `rememberFile` keep
  every descriptor they had, and each gains exactly one more, taking an `ExtractionRequest`. Full Kotlin synthetic constructor and `copy` ABI is **not** claimed for
  `SourceAnalysisContext`: adding a field to a data class changes `copy`/`componentN` and the
  synthetic `$default` constructor, so Kotlin code compiled against an earlier jar must be recompiled
  rather than swapped in. `SourceRevisionBinaryCompatibilityTest` runs the pinned legacy client and
  asserts exactly that split — both concrete constructors link, both `copy` descriptors raise
  `NoSuchMethodError` — with a negative control that removes the approved constructor and checks the
  same call site then fails. Five behavioural notes for hosts. **`POST /extract` answers with
  canonical stored ids.** This is a behavioural fix to the response content: the propositions the
  response names are the ones the store ended up with, so a merged or deduplicated extraction reports
  the canonical row's id where it used to report a pre-save extraction id that no read could resolve.
  The wire shape is unchanged — same fields, same types, same arity — and an extraction that creates
  fresh propositions answers exactly as it did.
  **The 400 responses of the extraction endpoints now carry a body.** `POST /extract` and
  `POST /extract/file` are endpoints that existed before this slice, and every 400 either of them
  returned had an empty body: a caller got the status code and no explanation with it. Every
  rejection raised by the new source-provenance validation now answers 400 with
  `application/json` holding a single string field — `{"error": "sourceRevision requires
  sourceLocator"}`, the new `ExtractErrorResponse`. That covers all six of those refusals on both
  endpoints: a revision with no locator, an unknown locator `kind`, a `connectorId` on a
  `uri`/`file`/`content` locator, a `connector` locator missing its `connectorId`, a blank locator
  `value`, and a source key or revision over the `SourceIdentityBounds` ceilings. The text is the
  wording of the check that refused, so a length rejection names the limit it broke and the length
  that broke it. Two older refusals keep the empty body they always had: blank `text` on
  `POST /extract`, and unparseable `sourceLocator` JSON on `POST /extract/file`. Who this affects:
  any client that reads the 400 responses of these two endpoints. A client that expected an empty
  body now receives JSON, and a client that deserializes 400 bodies has to accept the `error` field.
  A client that reads only the status code is unaffected, and no 2xx response shape moves.
  The `/why` response gains a
  `provenance` field, so a consumer that rejects unknown JSON properties needs to allow it; existing
  fields are unchanged, and the post-Wave-A shape is the baseline any later byte-for-byte `/why`
  promise re-bases onto. A revisioned REST request produces different chunk ids from the same request
  without a revision, so grounding rows written by the two are distinct; requests carrying no
  revision keep the ids they always had. And the async event path now reads `sourceLocator()` and
  `sourceRevision()` off the event, so a subclass that overrides them changes the provenance stamped
  on its propositions — subclasses that do not override are unaffected, since both default to null.
  No stored data migrates: `sourceRevision` stays absent from the JSON of a revisionless entry, which
  is byte-identical to what was written before.

- **EXPERIMENTAL.** One request object for the extraction entry points, carrying source provenance
  and versioned extraction content profiles (DICE #66). `ExtractionRequest` holds what a caller
  wants to say about one extraction on top of the text and the user it belongs to: the source it
  was read from, the revision of that source, and the content profile it runs under.
  `rememberText` and `rememberFile` each gain exactly one overload taking it —
  `rememberText(text, sourceId, user, additionalGrounding, perspective, mintNewEntities, request)`
  and `rememberFile(inputStream, filename, user, request)` — and the signatures those two methods
  already had are untouched, so every call and every override written against them keeps working.
  The next dimension extraction learns about arrives as a field on the request while the
  entry-point signatures stay where they are, so a host that overrides one keeps compiling and
  sees the new value without touching its override. A request checks its own coupling as it is
  built: a `sourceRevision` requires a `sourceLocator` whose key it matches, because a revision
  names a version of one specific source. A mismatched pair is refused there, before any entry
  point is called, so a caller can never reach extraction holding one. A profile is checked
  against nothing, because it is independent of where the material came from.
  `ExtractionRequest.NONE` is the empty request; a file call carrying it dispatches exactly as the
  three-argument call it resembles, so a subclass overriding only the six-argument `rememberText`
  still intercepts file ingestion the way it always did. Unintercepted, every call ends at the
  request-taking text method, so a host that wants one place to see all traffic overrides that
  one. `ExtractionContentProfileRef(name, version)` names a version of a host's
  content profile — the host's durable answer to what extraction of this kind of material should
  do. DICE carries the two strings and nothing else: it never looks a profile up, never reads
  policy out of it, and **selects no provider, model, or credential from it**. The host owns the
  catalog, authorizes the reference, and binds it to whatever it means. Identity is name and
  version together, so republishing a profile under a new version yields a reference the host can
  tell apart from the old one. The type validates non-blank components
  and caps their lengths (256 for a name, 64 for a version), because a reference is an identifier
  the host mints and no place to put a payload. It is not an authorization token, and may not
  carry a direct identifier or a dereferenceable secret. `SourceAnalysisContext` gains an optional
  `profile`, defaulting to null, with a `withProfile` copy helper. It is checked against no other
  field: a `sourceRevision` is coupled to its `sourceLocator` because it names a version of a
  specific source, while a profile is independent of everything else, and the `init` block says so
  without inventing a relationship the contract does not have. The request is the single door onto
  that field from the entry points, so nothing has to keep two copies of a profile in step. Adding
  the request argument by growing the existing declarations would have broken subclasses:
  `@JvmOverloads` emits every reduced-arity overload as `final` even on an `open` function, so
  folding it into `rememberText` would have turned the six-argument form into a final bridge and
  stopped a subclass overriding it from compiling. Each entry point is therefore two declarations,
  the one that was already there and the one taking a request.
  `SourceAnalysisRequestEvent` gains `profile()`, open and null-defaulted,
  and `ConversationAnalysisRequestEvent` takes it on its longer constructor. Both paths feed one
  `buildContext`, which is what makes the async path carry a profile identically; a test counts
  the accessor being read exactly once. Nothing downstream consults the reference — a test
  compares the whole context built with a profile against the one built without and asserts they
  differ in exactly that field. Profile, perspective, schema and tenant stay four independent
  dimensions: perspective describes conversational input, a profile is content policy, and a
  64-cell matrix test asserts every combination is constructible, that every ordered pair of
  dimensions realises its whole cross product, and that varying one leaves the other three
  identical. Design note:
  [docs/design/extraction-profiles.md](docs/design/extraction-profiles.md).
  **Compatibility: additive, with the same scoped ABI boundary as the Wave A slices.** Source and
  Java constructor-descriptor compatibility are claimed. `@JvmOverloads` on `SourceAnalysisContext`
  preserves every published constructor descriptor and adds one on the end; a test enumerates
  arities 3 through 12 (each with the trailing `DefaultConstructorMarker` Kotlin emits because
  `contextId` is a value class) and asserts all of them resolve. Every `rememberText` and
  `rememberFile` descriptor survives, with exactly one added per method name, on the end; a test
  pins the exact descriptor set of both names, asserts the request is always the last parameter,
  and asserts no entry point takes a locator, a revision or a profile as a loose argument.
  **Subclass-override compatibility is part of the claimed surface**: every signature that was
  overridable before this slice still is — `rememberText` at six arguments and `rememberFile` at
  three — and each method's request-taking form is overridable too. It is proven twice: a
  reflection test asserts `Modifier.isFinal` is false on all four and true on the reduced arities
  that were already final bridges, and a Java subclass in the compat suite overrides both
  signatures, so the suite compiling is the second proof (`javac` rejects `@Override` on a final
  method). A Kotlin pin calls every shape a caller could have written against those two signatures
  — every published arity, positional and named — from a subclass that overrides both, and asserts
  each call reaches the override. Being overridable is not the whole guarantee — the override also
  has to be reached — so two further tests pin the dispatch rule: a subclass overriding only the
  text entry points still sees file ingestion, and a file call carrying a request goes to the
  request-taking text method.
  `ConversationAnalysisRequestEvent` keeps its five-argument constructor and gains a
  six-argument form; its `sourceLocator` parameter relaxes from non-null to nullable, so a
  publisher can name a profile for material it has no typed source for, and every call that
  compiled before still compiles. Full Kotlin synthetic `copy` and `componentN` ABI is **not**
  claimed for `SourceAnalysisContext`: an added field rewrites `copy`, adds a `componentN` method,
  and changes the synthetic `$default` constructor, so Kotlin code compiled against an earlier jar
  must be recompiled rather than swapped in — the same half of the boundary #64 declined, pinned
  here by a test asserting exactly one `copy` remains and that it takes twelve arguments. No
  stored data changes and no migration is required: nothing serializes a profile yet, and no
  extractor DICE ships reads `context.profile` either. What exists is the means to build one: a
  host-supplied `PropositionExtractor` — the pluggable interface every consumer of
  `IncrementalPropositionExtraction` already wires in — receives the whole context on every
  `extract()` call, `profile` included, so a host can build a reader for its own content-policy
  identity today. DICE carries the profile to the extractor and stops there: every extractor DICE
  ships is behaviour-identical with or without one, and a host extractor that chooses to read
  `context.profile` is free to act on it however it defines. `ExtractionContentProfileRef` carries
  `@ApiStatus.Experimental` and its shape may still move. A Kotlin `@RequiresOptIn` marker would
  make that enforceable at the call site rather than advisory; DICE defines none today and the
  design note records it as an open question.

  **A run reference travelled with this slice for one round, was pulled back out, and has now
  returned on the request** (PR #94 review, then PR #95). `ExtractionRunRef` first shipped
  identity-only, as a loose parameter on every `remember*` entry point, ahead of the durable run
  store that would key on it. Nothing consumed it: `persistAndProject`, the method that actually
  saves extraction's output, takes only the pipeline's result and never sees the context that
  would have carried a run reference, so a caller passing one got it silently accepted and then
  dropped. The review's objection was to the parameters, so the parameters went, and
  `ExtractionRequest` arrived to make the next dimension cost no signature at all. `currentRun` is
  now a fourth field on that request, reaching `SourceAnalysisContext`, `SourceAnalysisRequestEvent`
  and `ConversationAnalysisRequestEvent` the way a profile does — and the entry-point descriptor
  sets are byte-for-byte the ones the paragraph above describes. That is the request object paying
  for itself: a new extraction dimension landed with no new method, no new arity, and nothing for a
  subclass or a Java caller to migrate. What has not changed is that nothing reads the run yet. It
  is identity only until the durable run store lands (DICE #67 and the run-model slices above it);
  a test pins that `persistAndProject` still has exactly one overload taking only the pipeline's
  result, so there is still no write for a run reference to reach.

  Full Kotlin synthetic `copy` and `componentN` ABI is **not** claimed for `SourceAnalysisContext`:
  two more fields rewrite `copy`, add two `componentN` methods, and change the synthetic `$default`
  constructor, so Kotlin code compiled against an earlier jar must be recompiled rather than
  swapped in — the same half of the boundary #64 declined, pinned here by a test asserting exactly
  one `copy` remains and that it takes thirteen arguments. No stored data changes and no migration
  is required: nothing serializes a profile or a run reference yet. Extraction, resolution, and
  revision ordering are behaviour-identical; a profile changes what a run is attributed to, not
  what it does. `ExtractionContentProfileRef` and `ExtractionRunRef` both carry
  `@ApiStatus.Experimental` and their shapes may still move while #67 lands. A Kotlin
  `@RequiresOptIn` marker would make that enforceable at the call site rather than advisory; DICE
  defines none today and the design note records it as an open question.

- **EXPERIMENTAL.** The extraction run model in `dice` core — the value types DICE #67's store,
  lineage and wiring slices build on. `ExtractionRun`, keyed by (`ContextId`, `ExtractionRunRef`)
  through the new `ExtractionRunKey`, records the profile version in force, the ordered source
  revisions it read, its lineage, prompt/schema/metamodel fingerprints, extractor/host/runtime
  identity, requested model configuration, pseudonymous subject references, experiment and cohort
  labels, status, timing, counts, invocation records, bounded sanitized failures, and an explicit
  replay-fidelity value. Nothing stores one yet: the lifecycle state machine and the store contract
  are the next slice, and no code constructs a run during extraction until the wiring slice.
  **Requested and observed model facts are separate types, structurally.**
  `ExtractionRequestedModelConfig` sits on the run header and holds what the host asked for —
  portable fields only: model and role, temperature, top-p, top-k, max tokens, presence and
  frequency penalties, thinking and selection fingerprints, and a timeout. There is no provider
  extension object, no settings blob and no free map, because that is where credentials, system
  prompts and whole SDK request bodies get persisted by accident; a provider-specific knob folds
  into one of the opaque fingerprints. What actually happened is an `ExtractionInvocationRecord`
  per attempt, carrying the configured service, `ExtractionModelUsage`,
  `ExtractionProviderResponseFacts`, timing and outcome. An invocation record has no field of the
  requested type and the two share no property name — `requestedModel` versus `responseModel` — and
  both are asserted by test, so nothing can present a setting as an observation. An absent observed
  field stays absent: a run that asked for a model and got no model name back records null rather
  than echoing the request. Ranges are validated where providers agree and left open where they do
  not, so a temperature of 2.0 is accepted. **Invocation identity comes from the call plan.**
  `ExtractionInvocationId` is (`invocationIndex`, `attempt`): the index is the call's ordinal in the
  run's plan, allocated by `ExtractionInvocationRecord.plan(n)` before any request goes out, and the
  attempt counts retries of that same call from 1. Completion order writes into identities that
  already exist — there is no factory taking a position in a result list, `retry()` carries the
  index forward and resets every observed field, `invocationsInPlanOrder()` reads the plan back out
  of records stored in arrival order, and a run rejects two records sharing an identity. That makes
  (`runId`, index, attempt) a deterministic child key for the store slices. **The root run
  reference is denormalized.** `ExtractionRunLineage` carries the run, its parent, what it
  supersedes, its pass index, and its root, following OpenLineage's `ParentRunFacet`, which also
  ships a root alongside the immediate parent so consumers need not walk the chain. The root is
  fixed at mint — a parentless run is its own root, a child takes its parent's root. `root()` and
  `childOf()` (`copy()` follows too, via `@ConsistentCopyVisibility`) are the only public way to
  mint one: `root()` sets the root to the run's own ref, `childOf()` derives it from the actual
  parent lineage it is handed, and either way no public parameter can carry a root that disagrees
  with the parent chain. That closes the public API only. Kotlin reflection, and a Jackson
  deserializer resolving the primary constructor the same way, can still construct one with a
  contradictory root — `ExtractionRunLineageTest` pins the gap openly. Nothing serializes this type
  today, so it affects a future store slice, not current wiring. Self-parenting and
  self-supersession are still rejected in the `init` block. Cycles longer than one need the other
  runs and stay with the store that walks the chains. **Privacy is
  a contract with an enforced floor.**
  `ExtractionActorRef`, `ExtractionRequestRef`, `ExtractionSessionRef`,
  `ExtractionPersonalizationRef`, `ExtractionDeploymentRef`, `ExtractionExperimentRef` and
  `ExtractionCohortRef` are all `ExtractionOpaqueRef`: host-minted tokens DICE compares and never
  parses, bounded to 256 characters and restricted to `A-Z a-z 0-9 . _ : ~ -`, which rejects an
  email address, a URL, a file path, a JSON fragment and a human name outright. The KDoc states what
  that does not prove — a value type cannot tell a pseudonym from a username — rather than implying
  a guarantee. A token's `toString` shows eight characters and a validation message never quotes the
  value it rejected. `ExtractionFailure` is a classified `ExtractionFailureCode` plus a bounded
  single-line detail; `fromThrowable`, the path DICE itself uses, never reads `Throwable.message`
  and records exception class names down a bounded cycle-safe cause chain, because a provider quotes
  the prompt back in its message. Tests extract from a fixture whose source text is known and assert
  no fragment of it, no address shape, no link shape and no long digit run survives into a
  field-by-field dump of a fully populated run. **Replay fidelity never claims exact replay.**
  `ExtractionReplayFidelity` is `NONE`, `METADATA`, `APPROXIMATE`; the strongest value is still
  approximate and `strongest()` returns it so appending a value cannot quietly strengthen the claim.
  **`ExtractionRunStatus` ships the four values only** — `RUNNING`, `COMPLETED`, `FAILED`,
  `CANCELLED` — with no transition rules, which belong to the store contract. `COMPLETED`'s meaning
  is stated on the value: every product the run's request called for is durably persisted or
  terminally disposed, written after persistence, so a run whose persistence never finished stays
  `RUNNING`. The two MLflow states DICE does not have are deliberate: `SCHEDULED` has no writer
  without a scheduler, and an externally killed run is `CANCELLED`, because stopping short of its
  products is the same fact whichever side pressed stop. OpenTelemetry's GenAI attribute names are
  not adopted: every `gen_ai.*` attribute is still at Development stability and the conventions
  moved to a separate repository in June 2026, so pinning a stored schema to them buys interop now
  and a migration later. **One cap rule**: every bound is a named constant on `ExtractionRunLimits`,
  checked in the `init` block of the type that owns the value, and an over-long value is rejected
  rather than truncated, because a shortened identifier is a different identifier. Identifiers cap
  at 256 characters, the one free-text failure detail at 512, and the three collections at 256
  source revisions, 1024 invocation records and 64 failures. The failure detail is the single
  exception to rejection and only on the way in: its factories clip it, the constructor still
  rejects. `sourceKey` and `sourceRevision` carry no length bound on `ExtractionRun`: their one
  bound lives on `SourceRevisionRef`, the type that owns those strings, which checks both halves
  against `SourceIdentityBounds` at construction (`docs/design/source-revisions.md`). A run accepts
  whatever that type accepts and adds no cap of its own, so a revision a query worked with can
  never fail on the way into run recording.
  One further string sits outside the rule too: `ContextId.value` — `ContextId` is a DICE-wide type owned by the
  agent framework — which matters because the tenant is half of `ExtractionRunKey`, so the store
  key is bounded on one side only. Two bounds are enforced away from the field they protect:
  `plan(count)` checks the invocation limit against the count before allocating anything, so a
  chunk-derived plan size cannot exhaust memory on its way to being rejected, and a run rejects a
  failure whose `invocation` names an identity it holds no record of, because a dangling reference
  reads as evidence about a call that nothing can join it to. A failure outside any model call
  names no invocation and is always accepted. Timing on an invocation record is an observation and
  may be absent on a terminal outcome: a `SUCCEEDED` attempt with no `startedAt` means the clock
  was not recorded, and requiring one would push callers to invent a duration. Design note:
  [docs/design/extraction-runs.md](docs/design/extraction-runs.md).
  **Compatibility: additive, new types only.** Nothing existing changes. No existing class gains or
  loses a member, no signature moves, no default changes, and no behaviour differs — this slice adds
  types to `com.embabel.dice.proposition.extraction` and touches nothing that was already there.
  Source, binary and Java compatibility are therefore all unaffected, and the scoped Kotlin ABI
  boundary the Wave A and B slices declared does not apply because no existing data class gained a
  field. No stored data changes and no migration is required: nothing serializes a run yet, and the
  first thing that will is the store slice. `ExtractionRun` is a plain class rather than a data
  class, so it publishes no `copy` or `componentN` to be compatible with later — deliberate, since a
  data class cannot defensively copy its collection parameters and a seventeen-field generated
  surface would pin an ABI while #67 is still moving; equality and hash are hand-written and a test
  varies each of the seventeen components in turn. Every type carries `@ApiStatus.Experimental`,
  asserted by a test that reads the class files because the annotation has class retention, and the
  shapes may still move while the remaining #67 slices land. The `@RequiresOptIn` question #66
  raised is unchanged and still open.
