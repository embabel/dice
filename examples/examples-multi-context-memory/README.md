# Multi-context memory

A support agent can recall an account's session alongside a shared handbook. The host looks up
the grant by tenant and principal before building the memory reference. The tool accepts query
text and a result limit. Each fact is a JSON line with `contextId` and `text`.

Run from the repository root after installing DICE as shown in the [examples guide](../README.md).

```bash
./mvnw -B -f examples/pom.xml -pl examples-multi-context-memory -am verify
```

Pass the service's authenticated request identity to `SupportMemory.forPrincipal`. The example
uses an in-memory table for the grants. A grant holds the authorized contexts and row budget.
A principal without a grant receives an error before a memory reference is created. The tests
seed the store and replay recorded arguments through Embabel's `Tool` interface.

| Test | What it demonstrates |
|---|---|
| `theSameFactIsReturnedWithBothContextLabelsWhileAnotherAccountStaysOut` | The authorized Maple session and handbook both state the same policy. Both copies keep their context labels. Cedar's records stay out even when the tool arguments include Cedar's context ID. |
| `eagerPromptReadsUseTheSameScopeAsTheTool` | A handbook-only grant returns handbook facts in both the prompt and tool. |
| `grantsUseBothPartsOfTheAuthenticatedIdentity` | Cedar's authorized case owner can recall Cedar's fact. A Maple-only principal in the Cedar tenant receives no grant. |
| `theHostControlsTheLimitAndTheCountStaysHonest` | The prompt reports more than two matching facts when the count exceeds the two-row budget. The tool returns an error with the cap. A caller can retry with a smaller limit. |
