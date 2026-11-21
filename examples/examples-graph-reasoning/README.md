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

The test also runs the default projection policy. Its confidence floor excludes the plan claim,
so the projected theory starts with only the two remaining input facts.

## Use this in an application

1. Resolve the tenant or account from the authenticated request. Use that context for
   `GraphQuery.of(store, contextId)` and to select the propositions that enter the Prolog
   projection. Keep each context's projected facts separate.
2. Declare a rule the application owns. Use the conclusion's recorded inputs to explain the
   result and the rule hash to identify the policy that produced it. The application decides
   whether to act on that result.
3. Handle `Evidence.Missing` before deciding. Check whether the required facts were present and
   satisfied the projection floor. In an approval flow, send an unresolved request for review.

The example starts with grounded propositions. In an application, keep the source record
available under its grounding id so the explanation can lead a reviewer back to the message.
