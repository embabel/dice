# Corrections and History

A desk that hears about one customer account, "acme", from the billing system and from support
chat. Some messages repeat what is already known, some back it up, some contradict it, and some
report that it changed. DICE revision compares each new fact with the stored ones and decides
which, and the trust it gives each fact depends on where the fact came from.

## Run

```bash
./mvnw -B -f examples/pom.xml -pl examples-corrections-history -am verify
```

The example runs as tests. `CorrectionsApplication` is the Spring Boot configuration they start.
`CorrectionsDesk.tell(source, messageId, text)` runs one message through the pipeline, stores
the outcome and returns what revision decided for each fact.

## Modules

The example is one package, `example.dice.corrections`. `ModularityTests` uses Spring Modulith
to keep any package added under it free of dependency cycles.

| Package | Public types | Package-private |
|---|---|---|
| `corrections` | `CorrectionsDesk`, and the schema types `Customer`, `Plan` | `CorrectionsApplication`, `CorrectionsMemory`, `PredicateStampingReviser`, `SourceAuthority`, `StoryEmbedder` |

## DICE usage

| Step | Where | What to read |
|---|---|---|
| Define a schema | `CorrectionsMemory.correctionsSchema` | Two domain types passed to `DataDictionary.fromClasses`. The model tags mentions with these names. |
| Give the fact store an embedder | `CorrectionsMemory.propositionRepository`, `StoryEmbedder` | `InMemoryPropositionRepository` scores stored facts by cosine similarity. `StoryEmbedder` gives each story sentence a fixed vector in place of a real embedding model, so the same facts are similar on every run. |
| Revise new facts | `CorrectionsMemory.propositionReviser`, `CorrectionsMemory.propositionPipeline` | `LlmPropositionReviser` joined to the pipeline with `withRevision`. Each new fact comes back as new, merged, reinforced, contradicted or generalized. |
| Weigh sources | `SourceAuthority` | An `AuthorityResolver` that reads each fact's `ConnectorRef`: billing is `PRIMARY` and chat is `DERIVED`. `AuthorityWeightedTrustScorer` turns the tier into a trust score of 0.9 or 0.6 under `dice.trust.score`. |
| Tell a change from a contradiction | `PredicateStampingReviser` | `TemporalConflictDetector` only treats a conflict as the world moving on when the fact's predicate is one that changes, such as `status`. DICE has no predicate hook in the pipeline, so the reviser is wrapped and stamps `status` on every fact that mentions a plan. |
| Resolve entities | `CorrectionsMemory.entityResolver`, `CorrectionsDesk.tell` | `EscalatingEntityResolver.withoutVector` looks mentions up by name, so every "Acme" resolves to one entity. |
| Store as one unit | `CorrectionsDesk.tell` | `processText` returns unsaved results. `PersistUnitWriter.inMemory` stores the facts, including the originals a contradiction marked `CONTRADICTED`, and the new entities as one `PersistUnit`. |

`PersistUnit` and `PersistUnitWriter` are marked experimental in DICE.

## Behaviour the tests assert

| Test | Behaviour |
|---|---|
| `ModularityTests.verifiesModuleStructure` | The package structure has no dependency cycle. |
