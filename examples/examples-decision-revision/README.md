# Decision revision

A desk that remembers what one account's messages say, where an Embabel decision, not DICE's
built-in LLM reviser, decides what each new fact means for the facts already stored.
`DecisionReviser` is a plain Java `PropositionReviser`. DICE needs no hook for it:
`PropositionPipeline.withRevision` takes any reviser.

## Run

```bash
./mvnw -B -f examples/pom.xml -pl examples-decision-revision -am verify
```

The example runs as tests. `DecisionRevisionApplication` is the Spring Boot configuration they
start. Every fact belongs to the account `acme`, which is the DICE context.

## Modules

The example is one package, `example.dice.decisions`. `ModularityTests` uses Spring Modulith
to verify its structure, as the other examples do.

| Package | Public types | Package-private |
|---|---|---|
| `decisions` | `RevisionDesk`, `DecisionReviser`, and the schema types `Customer`, `Plan` | `DecisionRevisionApplication`, `RevisionMemory` |

## DICE usage

| Step | Where | What to read |
|---|---|---|
| Define a schema | `RevisionMemory.revisionDictionary` | Two domain types passed to `DataDictionary.fromClasses`. |
| Plug in a reviser | `RevisionMemory.propositionPipeline` | `PropositionPipeline.withRevision` with `DecisionReviser` and the in-memory fact store. |
| Find candidates | `DecisionReviser.revise` | `PropositionQuery` for active facts in the same context that name one of the new fact's resolved entities. The store has no embedder, so there is no similarity search. A fact with no resolved entity has no candidates. |
| Ask the decision | `DecisionReviser.revise` | One choice per candidate (duplicate, reinforces, contradicts, unrelated), asked once through `ai.decisions().byRole("dice-revision")`. No candidates means no call. |
| Revise | `DecisionReviser.revise` | The first candidate that is not unrelated is merged, reinforced or contradicted with the built-in reviser's arithmetic, built with `absorbEvidence` and `toDraft()`. A pinned fact is never contradicted down. |
| Fail safe | `DecisionReviser.revise` | A failed decision, or any answer that is not one of the four options, stores the fact as new and logs a warning naming it. No stored fact changes. |
| Store | `RevisionDesk.tell` | `processText` returns unsaved results, and `PersistUnitWriter.inMemory` stores them as one unit. |

| Relation | Confidence of the stored fact | Decay |
|---|---|---|
| duplicate | `max(c, min(c + 0.3 * new, 0.99))` | `d * 0.7` |
| reinforces | `max(c, min(c + 0.1 * new, 0.95))` | `d * 0.85` |
| contradicts | `max(c * 0.3, 0.05)`, status `CONTRADICTED` | `min(d + 0.15, 1.0)` |

## Behaviour the tests assert

| Test | Behaviour |
|---|---|
