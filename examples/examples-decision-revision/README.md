# Decision revision

A desk that remembers what one account's messages say, where an Embabel decision, not DICE's
built-in LLM reviser, decides what each new fact means for the facts already stored.
`DecisionReviser` is a plain Java `PropositionReviser`. DICE needs no hook for it:
`PropositionPipeline.withRevision` takes any reviser.

For an effective-dated change the application has already accepted, use
[the SQLite world progression example](../examples-backend-swap/README.md#record-an-accepted-plan-change).

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
| `aFirstFactAboutAcmeHasNothingToCompareWithSoItIsNewAndNoDecisionIsAsked` | "Acme is on the Team plan" has no stored fact about Acme to compare with. It is stored as new with confidence 0.6, and the only model call is the extraction. |
| `sayingTheSamePlanAgainMergesIntoTheFirstFactAndRaisesItsConfidence` | "Acme uses the Team plan" is answered `duplicate`. The first fact keeps its id and text, its confidence goes from 0.6 to 0.78, its decay from 0.1 to 0.07, its reinforce count to 1, and it is grounded in both messages. The duplicate is not stored on its own. |
| `aRenewalOfThePlanReinforcesTheFirstFact` | "Acme renewed the Team plan for a year" is answered `reinforces`. The first fact's confidence goes from 0.78 to 0.84. |
| `movingToAnotherPlanContradictsTheFirstFactAndStoresTheNewOne` | "Acme moved to the Enterprise plan" is answered `contradicts`. The first fact becomes `CONTRADICTED` with confidence 0.252, and the new fact is stored `ACTIVE`. |
| `aPreferenceIsUnrelatedToThePlanSoItIsNewAndTheContradictedFactIsNotAskedAbout` | "Acme prefers email over phone" is answered `unrelated` and stored as new. Its only candidate is the Enterprise plan fact, because a contradicted fact is no longer active. |
| `aDecisionReplyThatIsNotJsonStoresTheFactAsNewAndChangesNothingStored` | "Acme has 40 seats on the Enterprise plan" has two candidates, and the reply `this is not json` fails the whole decision. The fact is stored as new, no stored fact changes, and a warning naming the fact is logged. |

| `hearingAMessageTheFactAlreadyHoldsAgainLeavesTheFactAsItIs` | A fact told again, whose only evidence is a message the stored fact already holds, is answered `duplicate`, but the stored fact comes back unchanged: the same confidence, decay and reinforce count. A retry is not more support. |
| `newEvidenceForAFactAtTheConfidenceCapStillRestartsItsDecay` | A fact at confidence 0.99 that has sat unused for a month gets new evidence. Its confidence cannot rise, but its decay restarts from now, so its effective confidence is back near 0.99. |
| `contradictingAFactAlreadyAtTheConfidenceFloorStillCountsAsARecentChange` | A fact already at confidence 0.05 is contradicted. Its confidence stays at 0.05, but the change of status still counts as a revision, so a query for facts revised since the call finds it. |

Each of the last five story messages makes exactly one decision call. Candidate ids are random,
so the tests never assume which candidate comes first. The last three tests ask the reviser
directly, each against a store of its own.

## Compared with the built-in reviser

DICE's `LlmPropositionReviser` finds candidates by embedding similarity and asks its own prompt.
This reviser needs no embeddings: a candidate is any active fact that names one of the same
entities. The question is yours too, so the options, their wording and what each one does are
rules you can read in `DecisionReviser`. The confidence and decay arithmetic is the built-in
reviser's, and like it, a change of confidence records a content revision on the stored fact.
