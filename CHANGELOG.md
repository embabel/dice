# Changelog

Notable changes to DICE. Each entry states its compatibility impact on consumers
(anything tracking `0.2.0-SNAPSHOT`): **additive** (safe to
pick up), **behavioral** (same API, different runtime behavior — read the note),
or **breaking** (consumer change required; the entry links the migration notes
and the consumer PRs that deliver it).

## Unreleased

### Added

- `dice-metamodel` module, first slice of schema versioning: `MetamodelVersion`
  content-hash stamping with per-type governance selection, the declared-schema
  opt-in contract, and the `MetamodelVersionStore` contract. Pure JVM.
  **Compatibility: additive.** New module; no existing API touched.

- Declared renames in `dice-metamodel`. **EXPERIMENTAL** (shape may change
  before 1.0): `SchemaAliases`, `PropertySignature.aliases`, and
  `MetamodelVersion.entityTypeAliases`. A declaration states the names a type or
  property used to go by, so a later comparison pairs a rename instead of
  reading it as a removal and an addition.
  **Compatibility: additive.** `contentHash` is unchanged for any schema that
  declares no aliases — the new hash blocks serialize only when non-empty, and
  the pinned golden digest is asserted unchanged, including for a stamp rebuilt
  through the public constructor. The new constructor parameters carry
  `@JvmOverloads`, so the existing `PropertySignature(String, Kind, String,
  Cardinality)` and `MetamodelVersion(String, List, Map, Map, List)` descriptors
  survive. `SchemaAliases` reaches `MetamodelVersion.from` and
  `DeclaredSchema.from` through separate overloads that take it as a required
  parameter, which leaves the shipped one- and two-argument forms and their
  Kotlin `from$default` synthetics byte-identical; widening those functions with
  a third defaulted parameter would have replaced
  `DeclaredSchema.Companion.from$default(Companion, DataDictionary,
  GovernedTypeSelector, int, Object)` with a wider descriptor and broken any
  caller already compiled against it. `MetamodelJavaCompatTest` calls every
  static form. The changed Kotlin synthetic constructor, `copy`, `copy$default`
  and `componentN` signatures on `PropertySignature` are the accepted boundary:
  Kotlin callers recompile, and no consumer holds a compiled reference to them.

- Schema attribution mechanism: per-proposition version is answered through the
  run that produced the proposition (PRODUCED_BY_RUN). The run record carries
  the declared schema's content hash, resolved by the extraction coordinator from
  the host's DeclaredSchemaSource. The `DiceMetadataKeys.METAMODEL_VERSION`
  metadata key is removed; lineage answers per-proposition attribution.
  **Compatibility: breaking.** The key is no longer available; code holding it
  must migrate to extraction-run queries.
- Drivine/Neo4j-backed `MetamodelVersionStore` in `dice-storage`
  (`DrivineMetamodelVersionStore`): stamps persist as `(:MetamodelVersion)` nodes,
  MERGEd on the natural key `(schemaName, contentHash)`, so a re-stamp updates in
  place. `latestVersion`, `versionHistory` and `findVersion` all resolve in Cypher.
  History is ordered by a persisted per-schema sequence, taken off a
  `(:MetamodelSchemaCounter)` node in the same statement that creates the version;
  `savedAt` and `savedAtEpochMillis` are informational, and nothing sorts on them.
  An idempotent re-save leaves the counter and the sequence alone. Concurrent saves
  of one version leave one node. Hosts must declare three uniqueness constraints:
  `MetamodelVersion(schemaName, contentHash)`, `MetamodelSchemaCounter(schemaName)`,
  and `MetamodelVersion(schemaName, sequence)`.
  Declared aliases persist at both levels: the version-level `entityTypeAliases` map
  as its own node property, and a property signature's former names as a fifth
  `aliases` field inside the stored signature. Both are written only when they hold
  something, so an alias-free stamp writes exactly the properties this mapper wrote
  before aliases existed, and a node from that older build reads back as a stamp
  declaring none. Aliases feed `contentHash`, and the mapper recomputes the hash from
  the persisted fields, so a stamp that failed to store them would be unreadable for
  good — pinned by an integration test that writes a row in the old four-field shape
  through raw Cypher and reads it back, one that round-trips a stamp carrying both
  alias kinds, and one that removes the stored alias map and asserts the integrity
  check rejects the row.
  `savedAt` and `savedAtEpochMillis` keep their existing behavior: set on create,
  untouched by a re-save. `dice-metamodel` gains `InMemoryMetamodelVersionStore`, the
  reference implementation of the store contract, promoted from a private class in
  that module's own tests. `AbstractMetamodelVersionStoreContractTest` runs one suite
  against both stores.
  **Compatibility: additive.** New classes, and a new `dice-storage` → `dice-metamodel`
  module dependency; no existing API touched. Stored nodes stay readable: every
  property that existed before keeps its name, meaning, and encoding, and the two
  new alias fields are absent when nothing declares them.
- Schema diffing contracts in `dice-metamodel`: `MetamodelDiffer` compares two declared
  stamps and returns a `MetamodelDiff`, an ordered, canonically sorted list of sealed
  `MetamodelChange` entries. The taxonomy covers property *signatures* as well as names.
  Alongside `EntityTypeAdded`/`Removed`/`Modified` and `RelationshipAdded`/`Removed`, a
  `PropertySignatureChanged` pairs `before` and `after` for a property that kept its name
  and changed value type, cardinality, or value-vs-reference kind, which a name-only diff
  reports as no change at all. `DeclaredObservedDiffer` compares a `DeclaredSchema` against
  an `ObservedSchema` snapshot of a live graph, separating drift (observed but undeclared,
  actionable) from unobserved (declared but empty, normal). That comparison is names-only
  on the observed side: a graph reports labels and relationship types, and cannot report
  declared property shapes. `ObservedSchemaSource` is the SPI a storage backend implements
  in a later slice; `ObservedSchema` is a plain value type, so tests drive the whole
  comparison from a canned snapshot. `StructuralMetamodelDiffer` implements both
  interfaces: deterministic, stateless, no database and no LLM. This slice adds no drift
  runner, no quarantine, no Spring wiring, and no new dependency.
  **Compatibility: additive.** New types in an existing module; no existing API touched.

- Declared renames in the diff, **EXPERIMENTAL** (shape may change before 1.0): five new
  `MetamodelChange` members — `PropertyRenamed(typeName, before, after)`,
  `EntityTypeRenamed(before, after)`, `EntityTypeAliasesChanged(typeName, before, after)`,
  `AmbiguousEntityTypeRename(formerNames, candidates)` and
  `AmbiguousPropertyRename(typeName, formerNames, candidates)`.
  A rename declared through `SchemaAliases` now pairs instead of reading as a removal and an
  addition. Pairing runs on what is left after the ordinary name matching and needs an
  exclusive claim on both sides, among the claims in the running: the old name is claimed by
  one new name alone, and that new name claims one old name alone. A surviving type's alias on
  the removed name, and a property name carrying two signatures, never enter the running and so
  contest nothing. Anything else is contested and pairs nothing — two new
  types both declaring `Person` as a former name, one new type claiming two old names that
  were both live, or claims that chain the two together. The contested names all report as
  ordinary additions and removals, and one `AmbiguousEntityTypeRename` or
  `AmbiguousPropertyRename` carries the whole group, so a declaration the differ set aside is
  visible rather than silent. It reports rather than throws: a schema in this state stamps
  cleanly today, and a caller comparing two historical stamps out of a store cannot edit
  either side. A property name the type-merge path holds two signatures for is out of the
  running before claims are read and falls back to a removal and an addition. Paired
  properties are excluded from
  `EntityTypeModified.addedProperties`/`removedProperties`, and an entry left empty by that
  is not emitted. Type pairing matches the whole accumulated alias set, so a type renamed
  twice still pairs across stamps that aren't adjacent, and it suppresses the
  `EntityTypeAdded`/`EntityTypeRemoved` pair, reporting the type's other deltas under the new
  name. After pairing, the older version is compared modulo the renames the diff found: old
  name for new is substituted in `Kind.REFERENCE` signature targets and in label sets, and
  nowhere else. A delta that vanishes under the substitution folds into `EntityTypeRenamed`,
  and one that survives reports in substituted form (a referrer that moved `A → D` while `A`
  was renamed to `B` reports `B → D`). `Kind.VALUE` type strings are left alone, so an entity
  type named `Date` renaming to `Timestamp` does not rewrite every property declared as
  holding a `Date` value. Rendered relationship descriptors are left alone too, so a
  relationship touching a renamed endpoint still churns as a removal plus an addition; the
  names inside a descriptor are free text and are never parsed. Alias-only edits have
  representations, so an empty diff and an equal `contentHash` keep meaning the same thing: a
  property whose aliases alone moved is an ordinary `PropertySignatureChanged`, and a type's
  is an `EntityTypeAliasesChanged`. Declared former names join the declared side of
  `diffAgainstObserved`, so data still carrying a renamed type's old label is not drift.
  `MetamodelDiff.touchedEntityTypes` covers the new kinds, and contributes both names of a
  rename and every name in a contested claim. `MetamodelDiff` gains four typed accessors —
  `ambiguousEntityTypeRenames`, `ambiguousPropertyRenames`, `addedRelationships` and
  `removedRelationships` — so the only kinds still reached through `filterIsInstance` are
  `EntityTypeRenamed`, `EntityTypeAliasesChanged` and `PropertyRenamed`, whose accessors land
  with the drift slice. A partition test pins the accessors against the change list on a diff
  holding every kind they cover.
  **Compatibility: breaking for external exhaustive `when` expressions.** `MetamodelChange`
  is a sealed interface, and five new members make any `when` over it outside this repo
  non-exhaustive until it handles them. Stated and accepted: the taxonomy is designed to be
  exhausted, and a consumer that silently treated a rename as an unhandled kind would treat
  it as harmless. Nothing else changes for a schema that declares no aliases — pairing and
  substitution are no-ops with an empty alias map, and the existing diff behavior, ordering
  and output are unchanged. Binary compatibility is untouched; consumers recompile.

- Type identity in the declared/observed comparison. `DeclaredSchema.entityTypeOwnLabels` holds
  the label each declared entity type writes onto a node: the declared name cut at its last dot,
  derived through the new `DeclaredSchema.ownLabelOf` helper. A JVM-backed type is declared by its
  class name, so a stamp holds `com.example.Person` while extraction records the mention as
  `Person` and the graph reports `Person` as the label. `diffAgainstObserved` put those two
  spellings side by side, which reported one healthy type twice — as an unobserved declaration,
  and as drift under the very label it was written with. Both directions now match on either
  spelling: a declared type counts as observed when the graph reports its declared name or its own
  label, and the drift exclusion covers the own labels of the declared types, of declared former
  names, and of the known-but-ungoverned types, which reach the check carrying no label set of
  their own. What gets reported is unchanged — an unobserved type comes back under the name the
  stamp declares. Two declared names differing only in their package share one own label and the
  set holds it once, which is what a graph does as well: a label carries no package, so a node
  written under either type reads back the same way. A schema whose declared names hold no dots
  behaves as it did before, pinned by the existing suite.
  Alongside it, `TypeIdentity`, **EXPERIMENTAL** (shape may change before 1.0): the interface a
  host implements to say which declared entity type an outside name means, for names arriving from
  a TypeScript API, an OpenAPI document, or any other system that spells types its own way. This
  slice ships it as a specification — KDoc, a stated contract (total, deterministic,
  round-tripping, many-to-one, exact string matching) and a worked OpenAPI example. Nothing in
  DICE implements it, calls it or wires it; the shipped differ compares on the own-label rule
  above, which covers the graph.
  **Compatibility: additive, carrying one behavioral fix.** One new property on `DeclaredSchema`,
  one new static helper, one new interface; no existing API touched. `contentHash` is untouched:
  own labels are derived on demand and reach no hash. The behavioral part is the fix itself — for
  a host declaring fully qualified type names, a drift check that reported such a type in both
  buckets at once reports it in neither. A host whose declared names hold no dots sees no change.
- Drift checking and quarantine contracts in `dice-metamodel`, plus the default runner and a
  reference sweep. **EXPERIMENTAL** (shape may change before 1.0) — opt-in: a host calls `DriftSweepCapable.sweep()`; nothing quarantines until then.
  **A drift check reports and changes nothing.** `DriftCheckRunner` has one mode:
  `run()` declares, stamps, observes, compares and persists a `DriftReport`, and holds no quarantine
  policy and no proposition store, so no path through it can move a proposition or the swept
  baseline. `DefaultDriftCheckRunner` stamps the declared version into the `MetamodelVersionStore` on
  every run, before writing the report, so a report's `versionHash` always resolves through
  `findVersion`; the write upserts on `(schemaName, contentHash)`, so an unchanged schema costs one
  idempotent write, and it leaves the swept baseline alone. `DriftReportStore` is the durable log,
  kept separate from the version store because stamps and reports have different volumes and
  lifetimes. Every read on it is bounded: `driftReports`, `globalDriftReports` and
  `driftReportsInContext` each take a `limit` and an optional `since`, and none has a default body,
  because filtering a limited page down to one scope in memory applies the limit before the filter
  and can report zero drift while plenty sits in the store.
  **A report carries both halves of the comparison.** `DriftReport` records declared-vs-observed
  drift (`driftedEntityTypes`, `driftedRelationshipTypes`) *and* `declaredDiff`, how the declaration
  itself moved since the last completed sweep, compared with a `MetamodelDiffer` against
  `SweptBaselineStore.sweptVersion`. The second comparison is what lets a property removed or
  narrowed, or a whole type dropped from the declaration itself, show up even when the live graph and
  the new declaration still agree on everything the graph currently holds — declared-vs-observed
  alone is blind to that case, since nothing about the type is undeclared and only its shape moved.
  `DriftReport.quarantineDiff(declaredVersion)` and `DriftCheckResult.quarantineDiff` merge the two
  into the exact comparison a sweep evaluates, so a report can no longer read clean while a sweep on
  the same state would quarantine. `hasDrift` keeps its narrow graph-truth meaning; the new
  `hasAnyChange` answers "would a sweep find anything at all to look at?".
  **Sweeping is a documented SPI a host invokes.** The new `DriftSweepCapable` defines
  `quarantineCandidates(contextId, mentionTypes, limit, afterId)` — bounded by `limit`, confined to
  one *required* `ContextId`, filtered on mention type by the backend, and ordered by proposition id
  so `afterId` is a usable cursor, all three stated as contract requirements in its KDoc —
  `applyQuarantine(decision)`, and `releaseFromQuarantine(propositionId)`, plus a defaulted `sweep`
  that pages through the candidates and persists what the policy flags. A store implements it when
  its backend can honour that, the way DICE's other opt-in store capabilities work; there is no
  whole-graph read and no whole-graph sweep. The mention types come from the new
  `DriftQuarantinePolicy.candidateMentionTypes(diff)`, so the store needs no policy knowledge of its
  own, and the policy contract states the invariant that makes bounded selection sound: a proposition
  whose mention types are all outside that set must evaluate to conforming.
  `PropositionStoreDriftSweep` is the in-memory reference implementation over any `PropositionStore`;
  it reads the one context and does the filter, ordering and page bound in the JVM, which is the part
  a durable backend pushes down. There is no Drivine implementation, and nothing in DICE calls a
  sweep on a timer or from auto-configuration.
  **Quarantine is its own lifecycle status, so the hold has an owner.** `PropositionStatus` gains
  `QUARANTINED`, and a drift sweep moves a stranded proposition there. A quarantine expressed as
  `STALE` plus a metadata note sat directly in the decay path: `DecayStatusPolicy` moves any `STALE`
  proposition back to `ACTIVE` once utility clears the recovery threshold, with no reason check, and
  `DecayManager` persists that. A confident proposition held for schema drift was therefore revived by
  the next decay sweep and held again by the next drift sweep, indefinitely. Recovery from quarantine
  was a side effect of that overlap, with no operation behind it. `QUARANTINED` closes it structurally: reads
  that filter on `ACTIVE` exclude it, `DecayStatusPolicy` never sees a `STALE` to revive *and* returns
  `null` for `QUARANTINED` outright (so a host that widens `DecaySweepConfig.targetStatuses` to every
  status still can't lift a hold), `pruneStale` leaves it alone, contradiction resolution and the
  abstraction pass read `ACTIVE` and never reach it, and the policy's already-quarantined check is the
  status alone — editing the reason metadata by hand releases nothing.
  `ProjectionLineageStaleCascade` treats `QUARANTINED` as terminal alongside `SUPERSEDED`,
  `CONTRADICTED` and `STALE`, so a quarantine still marks derived projection records stale.
  **Release is a real operation, and now the only way out.** `releaseFromQuarantine` restores the
  status a proposition carried before quarantine, clears its quarantine metadata, and announces the
  transition, in one write. The status to restore comes from the new
  `DriftQuarantineKeys.PREVIOUS_STATUS` (`dice.metamodel.quarantine.previousStatus`), which the policy
  writes onto the quarantined copy; a proposition with no usable value there is released to `ACTIVE`.
  Releasing a proposition that isn't quarantined answers `null`, so releasing twice is safe.
  **The swept baseline moves only for a completed sweep.** `sweptVersion` and `markSwept` live on the
  new `SweptBaselineStore : MetamodelVersionStore`, with no default bodies, and the host that ran the
  sweep is what calls `markSwept` once every context is reconciled. Splitting them off is the fix for
  a real hazard: a forwarding default answering `latestVersion` made every store look like it tracked
  a baseline while answering with write order, so a check's own stamp, a scoped sweep, or a crashed
  one each retired a change nothing had swept for. A store that implements nothing here now reports
  `declaredDiff = null` and gets the graph-truth half alone, which is the honest answer;
  `InMemoryMetamodelVersionStore` implements the capability with real swept-state semantics, and
  `latestVersion` alone still gets the wrong answer once a schema's declaration cycles back to a stamp
  it already used (`A` → `B` → `A` leaves `B` as the write-order latest, per `saveVersion`'s existing
  re-save contract, even though `A` is what's declared again).
  Quarantine itself is non-destructive, idempotent, and honors pinning. `DriftQuarantinePolicy`
  returns `QuarantineDecision`s (`Conforming` / `Quarantined` / `AlreadyQuarantined` / `Protected`);
  only `Quarantined` is an immutable `QUARANTINED` copy carrying a reason and the status it came from, for
  the caller to persist — the other three carry the proposition back untouched. A pinned proposition
  a lossy change would otherwise catch comes back `Protected`, untouched, per DICE's cross-cutting pin
  promise, with the same reason text so an operator can still see what it would have caught. The
  shipped `MentionTypeDriftQuarantinePolicy` fires on lossy changes only: a removed type, a type that
  lost labels or properties, or a property whose signature narrowed (type changed, value ↔ reference,
  or cardinality shrank along `ONE` ⊂ `OPTIONAL` ⊂ `SET` ⊂ `LIST`). An inherited label observed in
  the graph counts as declared, so it never quarantines. **Type identity now matches on both
  spellings of a declared name** — the name as declared and the label it writes onto a node
  (`DeclaredSchema.ownLabelOf`) — so a lossy change on a fully qualified `com.example.Person` reaches
  propositions whose mentions say plain `Person`, and a known-but-ungoverned `com.example.Sighting`
  is recognised as the declared type it is. This matches what `DeclaredObservedDiffer` already did on
  the declared side, so the two halves of a check agree about which type is which; a mention matching
  under the other spelling of its own type is ordinary matching and is never reported as a former
  name. Each proposition a sweep quarantines is announced to its `DiceEventListener` as a
  `PropositionStatusChanged`, emitted by the sweep itself so the signal doesn't depend on whether the
  injected `PropositionStore` happens to be wrapped in something like
  `EventEmittingPropositionRepository` — the default auto-configured store isn't. A proposition
  already `STALE` from ordinary decay is a fresh candidate, moves `STALE` → `QUARANTINED`, and a
  later release puts it back to `STALE`. A release announces the transition back. This is what lets a
  listener such as `ProjectionLineageStaleCascade` mark a quarantined proposition's projection records
  stale in turn.
  **The quarantine machinery lives in `dice` core, in `com.embabel.dice.spi`.**
  `DriftQuarantinePolicy`, `DriftQuarantineKeys`, `QuarantineDecision`, `QuarantineResult`,
  `DriftSweepCapable`, `MentionTypeDriftQuarantinePolicy` and `PropositionStoreDriftSweep` sit beside
  `StatusTransitionPolicy` and `SweepPolicy`, because moving a proposition between lifecycle statuses
  is what that package is for. The module dependency now points one way: `dice` depends on
  `dice-metamodel` to read a `MetamodelDiff`, and `dice-metamodel` is a leaf over the agent
  `DataDictionary` again with no view of the proposition model at all. A drift check therefore has no
  type through which it could reach a proposition, which is the structural half of "a check changes
  nothing". `embabel-agent-rag-core` remains a `provided` dependency of `dice-metamodel`.
  **Compatibility: additive on the released surface, with three source-breaking exceptions.**
  `DefaultDriftCheckRunner`'s constructor gains a *required* `metamodelDiffer: MetamodelDiffer`
  parameter (the declared-vs-previous comparison) — every existing caller must start supplying one.
  `QuarantineDecision` is a sealed interface gaining a fourth member, `Protected`, so an external
  exhaustive `when` over it needs a new branch to keep compiling — the same shape of change already
  accepted for `MetamodelChange` in this same Unreleased block. `PropositionStatus` gains
  `QUARANTINED`, so an exhaustive `when` over the enum needs a new branch too; inside DICE there was
  exactly one (`DefaultDreamLoopOrchestrator.statusStrength`, where `QUARANTINED` now ranks above
  every automatic retirement, since letting one overwrite a governance hold would drop the reason and
  the recorded prior status with it), and a host that matches on status exhaustively is the known
  consumer shape, which recompiles. Persistence is by enum *name* throughout (`PropositionGraphMapper`,
  `CollectorTraceRowMappers`, `LineageRowMappers`), so no stored value changes meaning. The
  quarantine types keep their names and move package, from `com.embabel.dice.metamodel` and
  `com.embabel.dice.metamodel.support` to `com.embabel.dice.spi`; they were added in this same
  Unreleased block and have never shipped. Everything else stays additive: `MetamodelVersionStore` is
  unchanged, so every existing implementation — `DrivineMetamodelVersionStore` included — keeps
  compiling untouched, and a backend opts into baseline tracking by implementing `SweptBaselineStore`
  when it is ready; `QuarantineResult` gains a `protected: List<QuarantineDecision.Protected>`
  parameter defaulted to empty, so existing callers of its constructor are unaffected.

- Rename-aware quarantine and a type-widening allow-list in
  `MentionTypeDriftQuarantinePolicy`, **EXPERIMENTAL** (behavior may change before 1.0).
  A declared rename no longer quarantines anything on its own: `EntityTypeRenamed` and
  `PropertyRenamed` are non-lossy per se, and `EntityTypeAliasesChanged` never quarantines.
  A paired property rename whose two signatures also differ is judged on that delta by exactly
  the `PropertySignatureChanged` narrowing rules, so `age: integer LIST` renamed to
  `years: integer ONE` still quarantines. Candidate matching goes through former names: a
  mention type is checked against its own name plus every current type name that used to go by
  it, read off the newer version's whole `MetamodelVersion.entityTypeAliases` map rather than the
  renames this particular diff carries — so a diff that only drops a property from a type renamed
  two stamps ago still reaches data written under the old name. Reading the declaration is safe
  because the reuse-collision refusal already guarantees an alias never names a live declared type.
  A **removed** type resolves from the older version instead, since the newer one has no entry for
  it: deleting `C` outright quarantines data labelled with every name `C` had gone by, excluding any
  the newer version declares as a live type of its own (reusing a retired name is legal once its
  claimant is gone, and data under it is judged as that live type's). Retiring a former name stops
  it matching — retirement says the schema no longer claims the name, and data still carrying it is
  reported by the observed-side comparison as ordinary undeclared drift.
  Former names accumulate, so a type renamed `A` → `B` → `C` declares `{A, B}` and a lossy change
  on `C`, or `C` being removed, quarantines data labelled `A`, `B` or `C` alike — however many
  renames deep the old label sits, and whether or not the rename rides in the same diff as the
  loss. A former name claimed by two live types is checked against both. The quarantine reason
  names which schema type an old name resolved to.
  Alongside it, four value type promotions are now treated as non-lossy: `int` → `long`,
  `float` → `double`, `Integer` → `Long`, `Float` → `Double`. Iceberg defines two of these as safe
  column promotions, `int` → `long` and `float` → `double`; the boxed pair is the same two as a JVM
  dictionary spells them, and Iceberg's reason carries over: every value of the older type has an
  exact representation in the newer one. Primitive-to-primitive and boxed-to-boxed only, so `int` →
  `Long` (boxing) and `Integer` → `long` (nullability) stay lossy, as does every reversal and
  every pair off the list. The list is scoped to `Kind.VALUE`, since one entity type is never a
  promotion of another. It is published as
  `MentionTypeDriftQuarantinePolicy.SAFE_TYPE_WIDENINGS` and pinned by a test that renders a
  real eight-field declaration through `PropertySignature.of`, so a rendering change in the
  upstream dictionary fails the build rather than quietly emptying the list.
  `MetamodelDiff` gains `renamedEntityTypes`, `entityTypeAliasChanges` and `renamedProperties`,
  the same convenience accessors the older change kinds already had.
  **Compatibility: behavioral.** Which change you see depends on whether the schema declares
  aliases. For a schema declaring none, matching is exactly what it was and the only move is
  permissive: a property whose value type went along one of the four allow-listed pairs no longer
  quarantines. Propositions an earlier sweep quarantined for one of those widenings stay
  quarantined — the already-quarantined check runs before any matching and nothing lifts a hold on
  its own, so no stored proposition changes state without an operator. To release them, call
  `releaseFromQuarantine` on those propositions and re-run the check; under the new rule they come
  back conforming. For a schema that declares aliases, matching now reaches
  data under a type's former names, so a proposition mentioning an old type name can newly
  quarantine when the renamed type lost something — which is the point: the old name is what the
  graph stores. Aliases arrive in this same Unreleased block, so no consumer can be in that state
  on a published build. The API is additive: three read-only accessors and one public constant,
  and no existing signature changed.
- Drivine/Neo4j-backed drift persistence in `dice-storage`. `DrivineDriftReportStore` keeps each
  check as a `(:MetamodelDriftReport)` node, MERGEd on the natural key
  `(schemaName, versionHash, capturedAt, contextKey)`, with `contextKey` either `global` or
  `ctx:<id>`, since a Cypher MERGE cannot key on a null. Prefixing every real context keeps the
  encoding injective, so no `ContextId` value can share a key with the global bucket and rewrite its
  scope. All three bounded reads are separate statements that push their scope into the query ahead
  of the `LIMIT`, which stops a schema whose recent history is mostly context-scoped from reporting
  zero global drift while plenty sits in the store. Ordering is newest first by capture instant,
  stored as `(epochSecond, nano)` so both the sort and an inclusive `since` window stay exact below
  the millisecond, with a per-schema `(:MetamodelDriftReportCounter)` sequence breaking exact ties so
  a limited page is repeatable. `DrivineObservedSchemaSource` takes the snapshot:
  `db.labels()`/`db.relationshipTypes()` unscoped, and per context the distinct mention types plus
  the edges whose `sourcePropositions` name that context's propositions. The unscoped path subtracts
  dice's own bookkeeping — every proposition, provenance, lineage, collector-trace and metamodel node
  label, and the `HAS_MENTION`/`DERIVED_FROM`/`SCORED`/`RETIRED_IN` edges — which keeps governance
  from observing the nodes its own last run wrote as domain drift. That subtraction goes by node
  shape: a label is hidden only while every node carrying it matches dice's shape for it, and an edge
  type only while none of its edges carries `sourcePropositions`, so an app governing a type called
  `Source` still sees it reported. `MetamodelSchema` collects the uniqueness constraints these stores
  need alongside the label list the observer excludes, keeping the two in one place. Still no Spring
  wiring; that arrives in the autoconfigure slice.
  **Compatibility: additive.** New classes in an existing module; no existing API touched. Hosts
  that already declared the three `MetamodelVersion`/`MetamodelSchemaCounter` constraints by hand can
  swap in `MetamodelSchema.specs()`, a superset. The drift-report store needs three more:
  `MetamodelDriftReport(schemaName, versionHash, capturedAt, contextKey)`,
  `MetamodelDriftReportCounter(schemaName)`, and `MetamodelDriftReport(schemaName, sequence)`.

- Three corrections to the Drivine drift-persistence slice above, closing gaps a review found before
  the pieces ever reached a released build.
  `DrivineObservedSchemaSource.observe` is now `@Transactional(readOnly = true)`, so the several
  queries a whole-graph or context-scoped observation issues run inside one Neo4j transaction and are
  assembled from it, the pattern `DrivineCollectorTraceStore.findEdgesByRun` already uses for the same
  reason. A concurrent graph write landing between separately-transacted queries could previously
  combine into an `ObservedSchema` describing a graph state that never existed at any single instant.
  This narrows the exposure to Neo4j's own per-transaction read-committed semantics — a write that
  commits while the transaction is still open can still reach a later statement inside it. The
  method's own KDoc states that residual honestly; Neo4j offers no full snapshot isolation to claim.
  Second, `ObservedSchema` gains `entityTypeBasis: EntityTypeBasis` (`GRAPH_LABELS` default,
  `MENTION_TYPES`), stating what kind of name `entityTypeNames` holds. The shared differ was comparing
  a context-scoped observation's `Mention.type` values against the same declared-labels set a
  whole-graph observation's Neo4j labels compare against, so a mention typed `Agent` passed drift
  detection when `Agent` was only a parent label of governed `Person` and nothing declared `Agent` a
  type of its own — an inherited-label escape hatch for undeclared mention types. `DrivineObservedSchemaSource`
  tags its context-scoped observation `MENTION_TYPES`; `StructuralMetamodelDiffer.diffAgainstObserved`
  now compares a `MENTION_TYPES` observation against declared type names and their declared former
  names only, with no widening to inherited labels. Third, `DrivineMetamodelVersionStore` tracks the
  reconciled baseline as a `sweptContentHash` property on the schema's own
  `(:MetamodelSchemaCounter)` node, moved only by `markSwept` and left untouched by an ordinary
  `saveVersion`, the same independence `InMemoryMetamodelVersionStore` already had. A durable store
  answering that question from write order would let every run's own history-stamping write — dry,
  scoped, or crashed alike — silently consume the very signal `DefaultDriftCheckRunner`'s
  declared-vs-previous comparison depends on, a gap the drift-runner slice above called out and
  deferred to this one.
  **Compatibility: additive.** `ObservedSchema` gains a defaulted constructor parameter under
  `@JvmOverloads`, so the pre-existing three-argument constructor survives in the compiled class
  alongside the new four-argument one, confirmed by running `javap` on the compiled class after
  compiling. Every existing Kotlin or Java caller and canned test fixture keeps compiling, keeping
  its prior (`GRAPH_LABELS`) reading. `DrivineMetamodelVersionStore` and `DrivineObservedSchemaSource`
  gain behavior on existing methods; no signature changed. `AbstractMetamodelVersionStoreContractTest`
  gains four `sweptVersion`/`markSwept` cases; the graph store now passes all four, and three of
  them — the null-until-swept case, the independence-from-a-later-new-stamp case, and the
  independence-from-a-later-re-save case — catch a store that answers from write order.

- Three more corrections to the same Drivine drift slice, from a later review round.
  First, a whole-graph observation now asks dice's own propositions for their distinct `Mention.type`
  values, alongside the label catalogue it already read. A mention type reaches `db.labels()` only
  once something projects a node for it, so an extraction that recorded `Ghost` and projected nothing
  left an undeclared type invisible to every unscoped check, while the context-scoped check on the
  same data reported it. The query holds both ends to dice's own shape, so a domain node wearing
  `:Proposition` contributes no mention types. The two kinds of name stay in separate sets:
  `ObservedSchema` gains `mentionTypeNames` (empty by default), `DrivineObservedSchemaSource` fills
  it on the unscoped path, and `StructuralMetamodelDiffer.diffAgainstObserved` judges labels under
  the observation's basis and mention types under the `MENTION_TYPES` rule, unioning what drifted.
  Merging them would have to pick one rule for both, and the label rule reopens what `MENTION_TYPES`
  exists to close: a mention typed `Agent` passing under a schema that governs `Person` with parent
  label `Agent` and declares no `Agent` type. An unscoped check and a scoped one now read mention
  types the same strict way, pinned by a differ test and by an integration pair that puts a
  `(:Person:Agent)` node in the graph and moves only the mention type between them.
  Second, the ownership catalog is derived from the storage definitions themselves, in the new
  `DiceOwnedSchema`, replacing the literal inventory of labels and properties the observer used to
  hold. A node fragment's shape is every constructor parameter dice's writer cannot leave out
  (declared non-null, with no default), so dice's `Source` shape is `key` **and** `kind`, and a
  host's own `(:Source {key: ...})` stays observed where the old key-only shape hid it. A
  Cypher-backed store's shape is the union of the properties its uniqueness constraints name. The new
  `LineageSchema` gives the lineage stores' labels and natural keys one definition site, and both
  stores build their MERGE patterns from it, so the key a record is upserted on and the key its
  constraint protects cannot drift apart.
  Third, `DrivineMetamodelVersionStore` declares `SweptBaselineStore`, the sub-interface the swept
  baseline moved onto, so `DefaultDriftCheckRunner` reads the durable pointer described above and a
  Drivine-backed host gets the declared-vs-previous half of a report once its first sweep completes.
  **Compatibility: behavioral.** `ObservedSchema` gains a fifth constructor parameter,
  `mentionTypeNames`, defaulted to empty under the existing `@JvmOverloads`, so every three- and
  four-argument constructor form survives and any caller that never fills it gets exactly the
  comparison it got before. `DiceOwnedSchema` and `LineageSchema` are new. No signature was removed
  or narrowed. A whole-graph check against a populated graph can report more than it did: mention
  types nothing ever projected, and a domain node sharing a dice label while missing a property dice
  always writes. Both were undetected drift before, so what appears is a real finding, and a scoped
  check's answer is unchanged. Hosts declaring the lineage constraints by hand can swap in
  `LineageSchema.specs()`.

- The bookkeeping exclusion in the Drivine drift slice above is now derived from the storage
  schemas an application registers, and a whole-graph observation counts only labels that carry at
  least one node. **EXPERIMENTAL** (shape may change before 1.0): the whole exclusion surface —
  `DiceStorageSchema`, `diceStorageCatalog`, and `DiceOwnedSchema`'s instance form — is opt-in
  governance wiring that only a host running drift checks touches.
  The exclusion used to come off a hand-enumerated list of three schema objects named in
  `DiceOwnedSchema`, with a KDoc claiming a new node fragment was the one case needing a line. That
  claim held for the three objects named and failed for the fourth object anyone added: a dice store
  arriving in another slice got its labels reported as domain drift on every unscoped check, forever,
  and both guard tests were built from the same list, so neither could see it. `DiceStorageSchema` is
  the contract each store's schema object now implements (`MetamodelSchema`, `CollectorTraceSchema`,
  `LineageSchema`), carrying its specs and the relationship types it writes for itself.
  `DiceOwnedSchema.of(registered)` reads the beans an application registered, and
  `DrivineObservedSchemaSource` takes the result as a required constructor argument, so a store
  landing in a later slice takes part by being registered and needs no edit to the drift machinery.
  `diceStorageCatalog` builds the Drivine catalog off that same bean list, which is what keeps a
  store's constraints and its exclusion from coming apart: one registration produces both.
  The KDoc now states the invariant the design can keep — the exclusion covers every schema the
  application registered, and a store whose schema is registered nowhere stays visible to
  observation, which is the right answer for nodes the application never declared. The four
  `@NodeFragment` classes of the core proposition store, and their `HAS_MENTION`/`DERIVED_FROM`
  edges, are owned unconditionally, since the observation reads propositions and mentions through
  their shapes to answer at all. `INFRASTRUCTURE_LABELS` stays an enumerated list, because a
  library's own bookkeeping has no dice schema to derive from.
  Second, the label side of a whole-graph observation now keeps only labels some node wears.
  `db.labels()` is a catalogue of label *tokens*, and on the `neo4j:2026.05` image these run against
  a uniqueness constraint mints its label there on an empty graph, probed directly. So a host
  declaring constraints for a type it has not populated reported that type as drift on its first
  check after first boot, having stored nothing. Constraint DDL is schema machinery an application
  declared; an observation reports what data the graph holds. The check is one label lookup per
  label, each stopping at the first node it finds.
  Third, both blind guards are replaced by `DiceStorageSchemaRegistrationTest`, in `dice-storage` and
  again in `dice-storage-autoconfigure`. It compares two independent things — every
  `DiceStorageSchema` singleton a classpath scan finds, and the beans the running Spring context
  registered — so a schema object that exists and is wired nowhere fails the build in the slice that
  adds it, and a hand-written `SchemaCatalog.of(SomeSchema.specs())` that ensures a dice store's DDL
  while leaving it out of the exclusion fails too. A matching scan holds
  `DiceOwnedSchema.CORE_NODE_FRAGMENTS` to every `@NodeFragment` in the storage model package. Four
  integration cases discriminate the two rules apart: a registered store's constraint-only label is
  not observed, its own nodes are excluded once they exist, a label no registered schema declares
  still drifts once nodes wear it, and a constraint-minted label nothing wears is not observed even
  though dice owns none of it.
  **Compatibility: behavioral.** `DrivineObservedSchemaSource` gains a required second constructor
  parameter; the top-level `DICE_BOOKKEEPING_RELATIONSHIP_TYPES` is gone, folded into
  `DiceOwnedSchema.bookkeepingRelationshipTypes`, and `DiceOwnedSchema` is a class with `of` where it
  was an object with `NODE_SHAPES`/`LABELS`. All three arrived in this same Unreleased block, so no
  published build carries them. A host wiring the observer registers its dice storage schemas as
  `DiceStorageSchema` beans and passes `DiceOwnedSchema.of(schemas)`; `TestApplication` shows the
  shape. A whole-graph check reports less than it did in one specific way — labels no node wears
  stop appearing — and reports no less about data the graph actually holds. `LineageSchema.specs()`
  gained the three lineage range indexes `DiceStorageAutoConfiguration` used to declare separately,
  so the DDL a graph-backed host ensures is unchanged and the two lists can no longer disagree.

- `dice-storage-autoconfigure` now depends on `spring-boot-transaction`. On Spring Boot 4, a
  `PlatformTransactionManager` bean alone does not activate `@Transactional`: the interceptor that
  reads the annotation lives in that separate module, which was missing here. Every `@Transactional`
  across `dice-storage` — around 78 of them — was silently inert in any application built on this
  autoconfiguration module, running with no transactional guarantees at all despite the annotations
  reading as if it did. `TransactionAutoConfiguration`'s own `@ConditionalOnMissingBean` on
  `AbstractTransactionManagementConfiguration` means it backs off cleanly for a consumer that already
  enables transaction management itself, so this addition is safe to double up on.
  **Compatibility: behavioral.** This is a genuine runtime change on upgrade: `@Transactional`
  methods across `dice-storage` start actually running inside transactions for the first time in
  any consumer using this autoconfiguration. `DrivinePropositionRepository.save`
  is direct proof that activation can expose a latent assumption written against the inert state: its
  dedup path ran a `TransactionTemplate` under an ambient (but previously inert) class-level
  `@Transactional`, with the stripe lock documented as held across the template's own commit. With
  transaction management genuinely active, the template's default propagation joined the now-real
  ambient transaction and deferred that commit past the lock release, reopening the exact race the
  KDoc claimed could not happen, and leaving the constraint-violation recovery path one participation
  away from `UnexpectedRollbackException`. Fixed by giving that `TransactionTemplate`
  `Propagation.REQUIRES_NEW`, so it always commits independently of whatever transaction is already
  open, proven with a test that pins a sibling save into the exact window between the writer's stripe
  lock release and its commit, forcing the overlap deterministically so nothing depends on scheduling
  luck: it fails against the joined-transaction behavior and passes with the independent one, every
  run.

  **Upgrade guidance for consumers of `dice-storage-autoconfigure`:**
  - Audit your own `@Transactional` usage too, alongside dice's own. Any `@Transactional` method in
    your application that quietly relied on nothing actually enforcing it starts running for real the
    moment this dependency lands on your classpath.
  - Do not wrap `GraphDecayManager.materialize`/`materializeAll` in your own `@Transactional`
    boundary. Its KDoc already warned against this, because the sweep's `CALL { ... } IN
    TRANSACTIONS` batching depends on running in its own implicit transaction; that warning had no
    teeth while `@Transactional` was inert, and an enclosing transaction now makes the batched
    Cypher fail outright.
  - `DrivinePropositionRepository.reembedAll()` now genuinely holds one Neo4j connection and
    transaction open for its entire run, including every call out to your `EmbeddingService`. If
    that service is remote or slow, budget for a database connection held that whole time. Write
    locks are a separate, narrower concern: the batch write only starts in `executeBatch`, after
    every embedding has already been computed, so lock contention with concurrent writers is
    confined to that last stretch near the end of the run.
  - `@Transactional(readOnly = true)` is worth knowing the limits of on this Drivine version
    (0.0.79), confirmed by reading the resolved jar's bytecode: `isReadOnly()` feeds a debug log
    line and nothing else, so a write reached through one of dice's read-only-annotated methods is
    still permitted — that part is unchanged. What genuinely does change is a consequence of the
    surrounding `@Transactional` advice becoming real: commit grouping and rollback. Such a write
    now lands inside the same real transaction as everything else that method does and commits
    together with the rest of the call, and a later rollback-triggering exception in that same call
    now rolls it back too. Previously, with no active transaction wrapping it, the write had already
    committed independently and stayed committed whatever happened next. Which exceptions trigger
    that rollback follows Spring's defaults: a `RuntimeException` or an `Error` rolls back and a
    checked exception commits, and custom rollback rules can override either behaviour.
- Spring Boot auto-configuration for schema governance: `MetamodelAutoConfiguration` in
  `dice-storage-autoconfigure`. It registers only when the application supplies a
  `DeclaredSchemaSource` bean, and then wires the loop: version store, drift-report store,
  observed-schema source, the two differ roles, quarantine policy, a `DriftSweepCapable`, the drift
  runner, and a `SchemaCatalog` carrying the metamodel uniqueness constraints. Every wired
  collaborator is `@ConditionalOnMissingBean`, so an application that defines its own keeps it.
  Settings live under `embabel.dice.metamodel`: `enabled=false` removes the beans in one
  environment while the declared-schema bean stays in place, and `drift.mode` is `off` or `observe`
  (the default), which picks whether a `DriftCheckRunner` bean is registered.
  Backend selection follows `embabel.dice.store.type`, the same switch the proposition store reads.
  Under `graph` the Drivine/Neo4j version store, drift log and observed-schema source are wired.
  Under the default in-memory backend an application that declares a schema still starts with no
  `PersistenceManager` anywhere: it gets `InMemoryMetamodelVersionStore`, the differ, the policy and
  the sweep, and it gets no drift log, no observed-schema source and no runner, because there is no
  live graph to observe.
  Nothing in the wiring runs a check or moves a proposition. A check happens when the application
  calls `DriftCheckRunner.run()`, and it reads, compares and writes a `DriftReport` and touches no
  proposition. Quarantine happens when the application calls `DriftSweepCapable.sweep` on a diff it
  decided to act on; there is no scheduler and no property that makes DICE sweep by itself. The
  wired sweep announces each status transition to every `DiceEventListener` bean on the context
  through a `CompositeDiceEventListener`, so a registered `ProjectionLineageStaleCascade` marks the
  projection records derived from a quarantined proposition stale.
  **Compatibility: additive.** No symbol that exists on the previous release changes shape or
  behavior. An application with no `DeclaredSchemaSource` bean sees no change at all. One that
  declares a schema and selects the graph backend needs the metamodel constraints, which the
  module's `SchemaCatalog` bean supplies, and a `PersistenceManager` on the context; a
  `PropositionStore` brings the sweep with it, and its absence leaves the rest of the loop working.

- An operator surface for schema governance. Until now the loop produced two kinds of inspectable
  state — drift reports and quarantined propositions — and offered no way to reach either outside a
  debugger. `GovernanceOperationsService` in `dice` is the one way in: `latestReports` and
  `reportsInContext` read the drift log, `currentDeclaredVersion` reports the declaration in force
  along with whether it has been stamped and which version the last completed sweep reconciled
  against, `runCheck` runs a check, and `releaseProposition` lifts one quarantine hold.
  `GovernanceController` puts it on HTTP under `/api/v1/metamodel` and `GovernanceTools` exposes the
  same five operations as `@LlmTool` agent tools; both call the one service, so the two front ends
  cannot answer differently.
  Reads are bounded: `limit` defaults to 20, must fall between 1 and 200, and a value outside that
  answers `400` naming the bound. `since` takes an ISO-8601 instant. A check reports and moves no
  proposition, so its response carries the full impact a sweep would evaluate — both drift sets, the
  declaration's own movement in `declaredDiff`, and the two merged into `sweepImpact`. A release is
  scoped by the context in its path before it writes, so a proposition in another context answers
  `404` untouched; a successful release restores the status the proposition carried before quarantine
  and answers the state it is in afterwards.
  Wiring: `MetamodelAutoConfiguration` registers the service and the tools under the governance
  conditions plus a `DriftReportStore`, `DriftCheckRunner`, `DriftSweepCapable` and
  `PropositionStore` on the context, and the new `GovernanceHttpAutoConfiguration` registers the
  controller when the application is a servlet web application with Spring MVC on the classpath. All
  three are `@ConditionalOnMissingBean`. A host that wants the loop with no HTTP surface excludes one
  auto-configuration by name:
  `spring.autoconfigure.exclude: com.embabel.dice.storage.autoconfigure.GovernanceHttpAutoConfiguration`.
  Building the context stamps nothing, writes no report and moves no proposition.
  There is deliberately no "release everything this report quarantined" operation. Nothing in the
  model ties a quarantined proposition back to the report whose application held it: a `DriftReport`
  has no identity beyond its natural key, and the reason a sweep writes names the two schemas and
  nothing about the check. See `docs/design/metamodel-wiring.md`.
  **Compatibility: additive.** New types and one new auto-configuration; no existing symbol changes
  shape or behavior, and an application with no `DeclaredSchemaSource` bean sees no change. The
  controller is not component-scanned, so nothing appears on an application's HTTP surface unless the
  governance loop is wired.

### Fixed

- `MetamodelAutoConfiguration` and the metamodel wiring tests referenced the drift quarantine types
  at their former home in `com.embabel.dice.metamodel(.support)`. They moved to
  `com.embabel.dice.spi` in the `dice` module when quarantine was given its own
  `PropositionStatus.QUARANTINED`, and the wiring was left pointing at the old package, so
  `dice-storage-autoconfigure` did not compile from clean. The affected tests also still asserted
  `PropositionStatus.STALE` after a drift sweep. Imports corrected and the post-sweep assertions
  moved to `QUARANTINED`. **Compatibility: additive.** No shipped symbol changes; the module now
  builds from a clean tree.
