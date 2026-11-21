# Governed chat

A support chat remembers an approved refund policy and incoming messages. The application picks
the policy, confidence threshold and prompt budget. DICE applies the gates, protects the pinned
fact in the prompt and records what happened.

## Run

Install DICE at the repository root, then run the example:

```bash
./mvnw -B -f examples/pom.xml -pl examples-governed-chat -am verify
```

The example runs as a Spring Boot test with recorded model replies and a fresh in-memory store
and audit log. The audit log for this run lasts as long as the application.

## Follow a turn

| Step | DICE API | What happens |
|---|---|---|
| Extract | `PropositionPipeline` | A message becomes a claim with grounding that carries the message id. |
| Apply the policy | `PropositionReviser`, `GateApplier` | `ApprovalPolicy` compares the named role with the pinned rule. A dispute goes to review and a low-confidence claim is rejected. |
| Store what passed | `GatedChunk.admitted`, `AuditingPropositionRepository` | The chat writes only admitted claims. The repository records the initial rule and the later pin. |
| Pick prompt memory | `Memory.withContextBudget` | The pinned rule stays when the budget leaves out the email preference. The selection reports that the policy alone exceeds the budget. |
| Explain a decision | `AuditEventListener`, `AuditLogQuery.history` | The rule names its grounding message. Each withheld claim has its own recorded disposition with a reason. |
| Read as of a time | `AuditSink.initializeBaseline`, `AuditLogQuery.beliefSetAsOf` | The host initializes a complete empty baseline before accepting its first message. An earlier read raises `AuditCoverageException`. |

`GovernedChat` is the turn boundary and supplies the example's small application API.
`GovernedChatApplication` wires the DICE components. `GovernedChatTests` exercises all four
messages and checks the resulting memory, admission decisions and history.

## Adoption

Keep the gate and pin policy in the application. Resolve the context from the authenticated
request before exposing the chat. Check that a claim belongs to that context before pinning it
or returning its grounding. Return a review result to the application for routing.

| Condition | Application behavior |
|---|---|
| A claim conflicts with the pinned policy | Return the proposed claim for review. Keep the approved rule available to the prompt. |
| The pinned facts exceed the prompt budget | Use the selection's `isOverBudget()` flag to handle the overflow before sending the prompt. DICE keeps the pinned facts. |
| A request predates audit coverage | Return an unavailable-history response. Use the baseline cutover time to tell the caller what history is covered. |

For restart-safe history, use the Drivine audit store with the same transaction manager as
the proposition repository. Stop writes to the context, capture its complete contents and
initialize the baseline before enabling the audited writer. A fresh context can start with
an empty snapshot. See the [audit design and setup](../../docs/design/durable-audit-history.md).
