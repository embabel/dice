# Durable proposition audit history

Experimental

Opt-in audit history answers what changed a proposition and what DICE held at a given instant.
Both answers use the same context-scoped record. Proposition writes carry old and new tracked
values. Gate verdicts and terminal extraction events add attribution and a reason. The live
repository remains the system of record for current data.

## Decision

A historical result promises a complete set of tracked proposition fields for one context.
A complete baseline is required because unchanged propositions from before adoption cannot be
inferred from later writes. An explicit baseline separates the start of coverage from the age of
the context. In-memory and Drivine implementations require the same baseline and use a
per-context sequence to order writes, including events with the same transaction timestamp.

The tracked fields are text, lifecycle status, raw confidence, importance, pinning and
`TemporalMetadata`. The history keeps neither grounding, provenance entries, nor the metadata map.
Reconstruction returns `AuditedProposition` with those tracked values, including propositions in
non-active states. Transaction timestamps place decisions on the audit timeline.
`TemporalMetadata` still describes when an assertion was observed or valid in the world.

## Adoption and cutover

1. Authorize the exact context in the application before fetching history or changing audit state.
   A context identifier selects the data. The application decides who can access it.
2. Instantiate an audit sink and wrap the repository with
   `AuditingPropositionRepository.wrapping(repository, sink, attributionProvider)`.
   Register `AuditEventListener` on the host's gate and extraction event paths for verdicts and run
   outcomes. Continue routing lifecycle writes, including decay, collection, and quarantine,
   through the wrapped repository. Configure the attribution provider with opaque identifiers and
   reason codes safe to retain. Use reason codes free of personal data in gate verdicts as well.
   Custom maintenance can bind its run using `AuditRunContext.withRun`.
   A deduplicating repository supplies its `AuditCanonicalLookup` explicitly. Use
   `repository.auditCanonicalLookup()` for Drivine, including when audit is outside another
   repository decorator.
3. Fence all context writers, take a complete snapshot, and initialize the baseline in that
   interval. Pause workers or have all writers share the same sink's write fence. A host certifies
   snapshot completeness, including any data a customized reader might otherwise filter.
   Pass an empty collection after verifying the context has no propositions.
4. Resume writers through the audited boundary. Preserve the same wiring across processes and
   maintenance jobs. Repository mutations outside the boundary leave the host unable to claim
   complete history.

For example, a host with authorized access and writers sharing the durable fence can initialize
history in Java as follows.

```java
audit.duringWrite(contextId, () -> {
    var cutover = Instant.now();
    var snapshot = repository.query(PropositionQuery.againstContext(contextId));
    audit.initializeBaseline(contextId, cutover, snapshot);
    return null;
});

var history = audit.history(contextId, propositionId);
var beliefSet = audit.beliefSetAsOf(contextId, requestedTime);
```

| Result | Meaning | Operator action |
|---|---|---|
| `AuditCoverageException` before cutover or beyond the observed horizon | The requested time is outside verified coverage. | Use a time inside coverage or report that history is unavailable. |
| `AuditCoverageException` after a missing record, inconsistent prior value, or missed write | The sink cannot certify a complete belief set. | Stop using historical results for that context. Investigate the write path. If coverage was invalidated, fence writers and install a complete snapshot at a later cutover. The existing record stream remains available. |
| `asOf` returns null | The claim was absent in the covered context at that time. | Report the claim as absent. |

Use `query(contextId, afterSequence, limit)` to page the recorded operations during an investigation.

## Transaction participation

`InMemoryAuditLog` uses a process-local fence and declares `BEST_EFFORT`. The history is lost on
restart. Use it with a repository whose writes take effect before they return, such as the
in-memory repository. When a host controls a separate transaction, use a joining sink so a
rollback also rolls back the audit record. Strict mode surfaces a recording failure. The wrapper
invalidates historical coverage when a write may have escaped a best-effort sink.

For Drivine on Neo4j or SQLite, opt in by registering a `DiceStorageSchema` bean for graph
ownership and a Drivine `SchemaCatalog` bean for its constraints and indexes. The host targets
that catalog at the datasource used by the proposition repository. Set the example app property
`host.audit.datasource` to its registered Drivine key, such as `audit-data` from
`database.datasources.audit-data`. If the application already aggregates schemas for that
datasource, include the audit schema in its targeted catalog.

```java
@Bean
DiceStorageSchema auditStorageSchema() {
    return AuditLogSchema.forRegistration();
}

@Bean
SchemaCatalog auditSchemaCatalog(
        @Qualifier("auditStorageSchema") DiceStorageSchema schema,
        @Value("${host.audit.datasource}") String datasource) {
    return DiceStorageSchemaKt.diceStorageCatalog(List.of(schema)).forDatabase(datasource);
}
```

Construct the audit store with the proposition repository's persistence manager and transaction
manager. SQLite requires `IMMEDIATE` transactions. Drivine's default SQLite datasource configures
this mode. SQLite updates the audit header as a JSON property document. Lock and counter
updates can rewrite that document, so a large baseline increases write cost.

```java
var audit = new DrivineAuditLogStore(persistenceManager, transactionManager);
var audited = AuditingPropositionRepository.wrapping(
        repository, audit, attributionProvider, AuditFailurePolicy.FAIL_WRITE,
        repository.auditCanonicalLookup());
```

The audit store declares `JOINS_HOST_TRANSACTION`. If a host transaction is already open, the
live change and record join it and become visible on commit. Otherwise the audited boundary opens
one for that context write. Use the same transaction manager and datasource for the repository
and audit sink. A failed joined audit operation refuses the outer commit, including when the host
catches the original error. A joined write requires Spring transaction synchronization. Terminal
extraction events are recorded in their own transaction after the run commits. Joined verdict
writes use strict failure handling. `LOG_AND_CONTINUE` applies to best-effort sinks and
post-commit events.

The sink samples `recordedAt` when assigning the audit position inside the write transaction.
It preserves nondecreasing timestamps within a context when the clock moves backward.
An outer transaction may commit later than the timestamps attached to its records. Replay
includes visible positions stamped at or before the requested instant. Call it outside a host
write transaction for a committed view. Inside a joined transaction, replay can also see the
caller's pending records. Treat the timestamp as audit write time. The Drivine schema also provides a fixed set
of lock stripes so two contexts cannot claim the same newly created proposition ID.
Drivine reads the retained records for a context under its audit fence when it verifies coverage
and replays history. Invoke `asOf` and `beliefSetAsOf` with a write-capable transaction manager,
outside a host read-only transaction. Record pages and per-proposition history use scoped reads.

## Erasure

The decorated repository's `clearByContext` clears the live rows and erases that context's
baseline and historical content. The durable sink performs these operations in the same
transaction. The surviving record includes the context, sequence, timestamp, optional opaque
actor, and counts of erased audit records and baseline propositions. Repeating an erasure without
new audit data returns the same tombstone. Deleting an individual proposition retains its scoped
history. The wrapper requires exact contexts for erasure. For broad erasure, call
`clearByContext` for each authorized context and handle other data stores and backups separately.

Each single-context batch acquires its identity fences in a fixed order before saving. Writes
and canonical results follow caller order. Mixed-context batches write one proposition at a time
unless the host opens an outer transaction. A host that makes multiple audited calls in one
transaction must handle aborts caused by overlapping locks and retry the entire transaction.
For whole-store re-embedding, pause audited writers and run the repository's maintenance
operation while the store is quiescent.

Applications that leave audit unwired keep their existing repositories, schema and event paths.
