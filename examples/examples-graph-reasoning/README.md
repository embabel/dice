# Graph reasoning

Three recorded claims link Acme, its support account, Dana, and the Basic plan. The example
follows a graph path from Acme to Dana, then asks a rule who should approve the Basic downgrade.

## Run

Install DICE at the repository root, then run the example:

```bash
./mvnw -B -f examples/pom.xml -pl examples-graph-reasoning -am verify
```

The tests seed an in-memory store from fixed claims with chat sources. The graph and Prolog
queries run locally.

## Traversal and deduction

| Step | DICE API | Result |
|---|---|---|
| Explore Acme | `GraphQueryTools.entityNeighborhood` | At depth one, the account is Acme's neighbour. |
| Find a path to Dana | `GraphQuery.pathBetween` and `GraphQueryTools.pathBetween` | Acme links to the account, which links to Dana. Both path edges are stored claims. |
| Explain the admin claim | `GraphQueryTools.whyExplain` | The claim names its grounding chat. The lineage includes the source locator. |
| Project the recorded facts | `RelationBasedGraphProjector` and `DefaultPrologProjector` | The ownership, administrator, and plan edges become Prolog facts. The example sets the projection floor to 0.8 to include the plan claim. |
| Ask the rule | `PrologTools` and `PrologEvidenceProvider` | Dana is the approver. The derived evidence carries `approver/2`, the bindings, and the three source claims. |

```prolog
approver(Customer, Admin) :-
    has_account(Customer, Account),
    administers(Admin, Account),
    uses(Account, basic).
```

The rule is an application policy for this example. It uses the same three recorded claims that
remain in the store after the query. The result is an experimental `Evidence.Derived` value
calculated at lookup. A goal the projected facts cannot prove returns `Evidence.Missing`.

The default projection floor excludes the plan claim. With only the ownership and administrator
relations projected, asking the same approval rule returns `Evidence.Missing`.

## Use this in an application

1. Resolve the tenant or account from the authenticated request. Use that context for
   `GraphQuery.of(store, contextId)` and to select the propositions that enter the Prolog
   projection. Keep each context's projected facts separate. The projection input determines
   which facts the rule can use. `RuleGoal.withContext` records the requested context in
   `Evidence.Missing`.
2. Version the application's approval policy and record that version with its decisions. Use the
   conclusion's recorded inputs to explain the result. The `theory` digest covers the loaded
   rules and facts, including the projected confidence and grounding facts. It can change when
   the facts change.
3. Handle `Evidence.Missing` before deciding. Check whether the required facts were present and
   satisfied the projection floor. In an approval flow, send an unresolved request for review.

The example starts with grounded propositions. In an application, keep the source record
available under its grounding id so the explanation can lead a reviewer back to the message.
