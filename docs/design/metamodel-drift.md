# Metamodel drift: checking a live graph against a declared schema

A drift check takes the schema an application declares, observes what a live graph is holding,
records where the two disagree, and, when asked to, pulls the propositions that disagreement
stranded out of normal use.

Stamping and diffing both work on declarations. A drift check is the step that reads the graph.

The shape of a run is fixed, and every step leaves something behind:

```mermaid
sequenceDiagram
    autonumber
    participant Caller
    participant Runner as DriftCheckRunner
    participant Declared as DeclaredSchemaSource
    participant Versions as MetamodelVersionStore
    participant Observed as ObservedSchemaSource
    participant Differ as DeclaredObservedDiffer
    participant Reports as DriftReportStore
    participant Policy as DriftQuarantinePolicy
    participant Props as PropositionRepository

    Caller->>Runner: run(dryRun, contextId)
    Runner->>Declared: declare()
    Declared-->>Runner: DeclaredSchema (stamp + bare rel names)
    Runner->>Versions: saveVersion(stamp)
    Note over Runner,Versions: saved before the report, so the<br/>report's hash always resolves later
    Runner->>Observed: observe(contextId)
    Observed-->>Runner: ObservedSchema (labels + rel types, one instant)
    Runner->>Differ: diffAgainstObserved(declared, observed)
    Differ-->>Runner: DeclaredObservedDiff (drifted vs unobserved)
    Runner->>Reports: saveDriftReport(report)
    Note over Runner,Reports: written on every run, including<br/>checks that find nothing
    alt live run and entity-type drift
        Runner->>Props: candidates (scoped or all)
        Runner->>Policy: evaluate(diff, candidates)
        Policy-->>Runner: STALE copies + reasons
        Runner->>Props: save(each quarantined copy)
    else dry run, or no entity-type drift
        Note over Runner: nothing is touched
    end
    Runner-->>Caller: DriftCheckResult
```

## The three tiers

Schema governance in DICE has three tiers, shipped in that order.

**Stamp and observe.** Capture the schema as a content hash and keep the history. See
[metamodel-versioning.md](metamodel-versioning.md).

**Detect and report.** Compare two declarations against each other, or a declaration against a live
graph, and record what you find. The drift check sits here, and it is the default: `run()` with no
arguments is a dry, whole-graph check that persists a report and changes nothing.

**Quarantine.** Act on a lossy change by marking the affected propositions stale rather than
deleting them. Off by default; `run(dryRun = false)` turns it on. This slice adds the contracts, and
the wiring that schedules a live run arrives with the autoconfigure slice.

Each tier builds on the one below it, and each is useful on its own. You can stamp for a year
without detecting, and detect for a year without quarantining. Rejecting undeclared types at write
time is still not on the list: extraction is LLM-driven, a type nobody declared is often a real
finding, and discarding it is the one thing that can't be undone later.

The prior art is RDF's SHACL, which validates data that already exists and reports each violation,
what was expected, and where, without blocking the write. A `DriftReport` is the same idea for a
property graph: it names the undeclared types, records the schema version they were judged against,
and stays on file whether or not anybody acts on it.

## Stamping the version before writing the report

A `DriftReport` records the `versionHash` of the declared schema it was measured against. Look that
hash up with `MetamodelVersionStore.findVersion` and a report from six months ago resolves back to
the exact schema shape expected when the observation was taken.

That works only while the stamp is in the store, so `DefaultDriftCheckRunner` saves the declared
version on every run, before it writes the report, including runs where the schema hasn't moved.

The cost is one idempotent write per check: `saveVersion` upserts on `(schemaName, contentHash)`, so
an unchanged schema re-saves onto its own key and stores nothing new. Stamping afterwards, or only
when the schema changed, would leave the first check after a schema change pointing at a hash
nothing recorded.

## What counts as drift

The comparison itself is [`DeclaredObservedDiffer`](metamodel-diff.md), and it is asymmetric on
purpose:

- **Drifted** — observed in the graph, never declared. Actionable. Data is sitting there whose
  declaring integration was removed or never registered, so nothing describes its shape or vouches
  for it.
- **Unobserved** — declared, with no instances at the moment. Informational: a declared type with no
  data yet is an ordinary state.

Inherited labels count as declared. A graph reports labels, and a type carries every label in its
hierarchy: declare `Person` with parent `Agent` and every `Person` node comes back carrying both.
Comparing observed labels against declared *type names* would report `Agent` as drift on a schema
nobody had touched, and a live run would quarantine sound propositions for it. So the declared side
of the comparison is the type names plus the full label closure those types declare.

## Every read of the drift log is bounded

`DriftReportStore` is the durable log: `saveDriftReport`, plus three reads that name their scope at
the call site — `driftReports` (everything), `globalDriftReports` (unscoped whole-graph checks only),
`driftReportsInContext` (one context). Three names rather than one method with a nullable context,
because `driftReports(schema, null)` would have meant "the global ones" while `driftReports(schema)`
meant "all of them": two near-identical calls with different answers.

Every read takes a `limit`, and optionally a `since` instant. There is no unbounded read. A drift log
grows once per check per schema forever, so an unbounded query works on a laptop and falls over after
a month of hourly checks.

That is also why none of the three has a default implementation. Filtering a limited page down to the
global reports in memory would apply the limit *before* the filter, so a schema whose recent history
was mostly context-scoped could report zero global drift while plenty sat in the store. The scope has
to go into the query, so every backend writes all three.

## Quarantine

A live run hands the drifted types to a `DriftQuarantinePolicy`. The shipped one,
`MentionTypeDriftQuarantinePolicy`, quarantines a proposition when one of its entity mentions names a
type a **lossy** change touched:

| Change | Lossy? |
| --- | --- |
| Type removed | Yes — nothing describes those mentions any more |
| Type lost labels or whole properties | Yes — a mention may have relied on what's gone |
| Property narrowed: type changed, value ↔ reference, or cardinality shrank | Yes — the new shape may not hold the old data |
| Type, label or property added | No |
| Cardinality widened (`ONE` → `OPTIONAL` → `SET` → `LIST`) | No — everything that fit before still fits |

That last row is the ordering the policy uses: the four cardinalities line up by what they can hold,
so moving up is safe and moving down can strand something. A list of three doesn't fit in a single
value, and a list collapsing to a set drops duplicates. The diff itself makes no judgement.
`MetamodelDiff` states that `age` went from `string` to `integer`; deciding whether that can strand
data is this policy's job.

Type changes count as lossy in **both** directions. We know the declared types moved; we don't know
how a backend stored the values or whether the new type can read the old ones, and guessing wrong in
the permissive direction leaves unreadable data looking healthy.

Two properties make this safe to run as routine maintenance:

- **Non-destructive.** Nothing is deleted and nothing is mutated. An affected proposition comes back
  as an immutable copy moved to `STALE`, annotated with a human-readable reason under
  `dice.metamodel.quarantine.reason`. Leaving drifted propositions in normal retrieval would corrupt
  query results; deleting them would destroy something a person might want to rescue.
- **Idempotent.** A proposition an earlier sweep quarantined comes back in its own
  `alreadyQuarantined` bucket, untouched and with its original reason intact, so it is neither
  re-flagged nor counted in `conforming`. To force one back through evaluation, clear its reason
  metadata first. Status alone is not enough to skip a proposition: ordinary decay also makes
  propositions stale, and those carry no reason and are still candidates. The classification doesn't
  depend on the diff in front of it: being already quarantined is a fact about the proposition, so
  an empty or purely additive diff still sorts one into `alreadyQuarantined`. Skipping the check on
  an empty diff would report quarantined records as conforming on every run that finds no drift.

The policy decides and doesn't write. The `STALE` copies come back to the caller, and the runner
persists them, which is how a dry run produces the same decisions while changing nothing.

The runner reads and writes those propositions through `PropositionStore`, the base persistence port,
rather than `PropositionRepository`. A drift check only reads by context or in bulk and saves;
requiring vector search, graph traversal and temporal query alongside would shut a plain
store-and-retrieve backend out of drift checking over capabilities it never uses.

## Scope

Every part of a run takes the same optional `ContextId`, and it means the same thing throughout: the
observed snapshot, the candidate propositions read for quarantine, and the persisted report are all
confined to that one context. A mis-declared schema in one context can only quarantine propositions
in that same context. Pass `null` and the check covers the whole graph.

## Using it

```kotlin
val runner = DefaultDriftCheckRunner(
    declaredSchemaSource = { DeclaredSchema.from(dataDictionary, governed) },
    versionStore = versionStore,
    observedSchemaSource = observedSchemaSource,
    differ = StructuralMetamodelDiffer(),
    driftReportStore = driftReportStore,
    quarantinePolicy = MentionTypeDriftQuarantinePolicy(),
    propositionStore = propositionStore,
)

// The default: dry, whole graph. Reports, changes nothing.
val result = runner.run()
if (result.hasDrift) {
    log.warn("undeclared in the graph: {} {}", result.driftedEntityTypes, result.driftedRelationshipTypes)
}

// Opt in to acting on it.
val live = runner.run(dryRun = false)
log.info("quarantined {} proposition(s)", live.quarantinedCount)

// What did the last week look like?
driftReportStore.globalDriftReports(schemaName, limit = 50, since = Instant.now().minus(7, ChronoUnit.DAYS))
```

`DriftCheckResult` reads its drifted types off the `report` it saved rather than keeping a second
copy, so what you log and what an operator later reads out of the store can't disagree.

The runner is stateless and schedules nothing. Running it repeatedly, or for different schemas at
once, is fine. Two concurrent checks of the same schema don't corrupt anything, since each captures
its own complete snapshot, but they duplicate work; serialize at the scheduling layer if that
matters.

## What comes next

`DriftReportStore` and `ObservedSchemaSource` are contracts here with no implementation yet. They
need a Drivine-backed report store, and an observer that asks Neo4j for its distinct labels and
relationship types. There is no Spring configuration in `dice-metamodel` either, so a runner is an
ordinary constructor call until the autoconfigure slice assembles one, with quarantine off unless a
host turns it on.
