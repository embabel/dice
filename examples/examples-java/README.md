# Java

A support desk that remembers what its tickets say. Filing a ticket publishes an event. A
listener extracts facts from the ticket with DICE and stores them under the ticket's account.
`CustomerMemory` reads them back one account at a time.

## Run

```bash
./mvnw -B -f examples/pom.xml -pl examples-java -am verify
```

The example runs as tests. `StarterApplication` is the Spring Boot configuration they start, with a
scripted model as the default model. `TicketMemoryTests` files four tickets for two accounts and
asserts what is remembered.

## Modules

The application is split by feature. Each package under `example.dice.starter` is a module.
The compiler keeps package-private types inside their module. `ModularityTests` uses Spring
Modulith to verify that the modules have no dependency cycle.

```mermaid
flowchart LR
    memory -- listens for TicketFiled --> tickets
    memory --> customers
    tickets --> customers
```

| Package | Public types | Package-private |
|---|---|---|
| `customers` | `AccountId`, and the schema types `Customer`, `Contact`, `Plan` | |
| `tickets` | `Ticket`, `Ticket.TicketId`, `Ticket.TicketFiled`, `Tickets` | |
| `memory` | `CustomerMemory` | `AccountEntities`, `MemoryConfiguration`, `TicketExtraction`, `UngroundedMentionLog` |

`tickets` does not depend on `memory`. It publishes `TicketFiled`, and `memory` listens.

## DICE usage

| Step | Where | What to read |
|---|---|---|
| Define a schema | `MemoryConfiguration.supportSchema` | Three domain types passed to `DataDictionary.fromClasses`. The model tags mentions with these names. |
| Build the pipeline | `MemoryConfiguration.propositionPipeline` | `PropositionPipeline` with an LLM extractor, an event listener, and ungrounded mentions dropped. |
| Handle pipeline events | `MemoryConfiguration.diceEvents`, `UngroundedMentionLog` | DICE events are republished as Spring events and handled with `@EventListener`. |
| Resolve entities | `AccountEntities`, `TicketExtraction.on` | `EscalatingEntityResolver` looks mentions up by name in the store it is given. Each account has its own store. |
| Extract once and store | `TicketExtraction.on`, `MemoryConfiguration.chunkHistoryStore` | The `ChunkHistoryStore` is checked for the ticket first. `processText` returns unsaved results, `persist` stores them, and only then is the ticket recorded as stored. |
| Query | `CustomerMemory` | `PropositionQuery` scoped to one context, filtered by status, with confidence judged at a given time. |

```java
var stored = HashKey.of(account.value(), Sha256ContentHasher.INSTANCE.hash(ticketId + "\n" + ticket.text()));
if (history.isProcessed(stored)) {
    return;
}

var entities = accountEntities.of(account);

var context = SourceAnalysisContext
        .withContextId(account.value())
        .withEntityResolver(EscalatingEntityResolver.withoutVector(entities))
        .withSchema(schema)
        .withMintNewEntities(true);

var extracted = pipeline.processText(ticket.text(), ticketId, context);
extracted.persist(propositions, entities);

history.recordProcessed(new ProcessedChunkRecord(
        BookmarkKey.of(account.value(), ticketId), stored, 0, 1, Instant.now()));
```

```java
propositions.query(
        PropositionQuery.againstContext(account.value())
                .withStatus(PropositionStatus.ACTIVE)
                .withEffectiveConfidenceAsOf(asOf)
                .withMinEffectiveConfidence(minConfidence)
                .orderedByEffectiveConfidence());
```

## Behaviour the tests assert

| Test | Behaviour |
|---|---|
| `keepsEachAccountsFactsToItself` | The account is the DICE context. A query for one account returns no fact from another. |
| `everyFactPointsBackAtItsTicket` | The ticket id is the source id, and each fact's grounding carries it. |
| `resolvesAMentionInALaterTicketToTheEntityFromAnEarlierOne` | "Acme" in the second ticket resolves to the entity the first ticket created. |
| `dropsAMentionItsSentenceNeverMakesAndReportsIt` | One recorded reply attaches "Ada Okafor" to a sentence that does not contain the name. The pipeline drops the mention, keeps the fact, and publishes `UngroundedMentionsFound`. |
| `givesEachAccountItsOwnEntityForASharedName` | Both accounts have a contact called Ada Okafor. Each account's mention resolves to an entity in its own store. |
| `filingTheSameTicketAgainStoresNothingNew` | A ticket filed twice is extracted once. The second filing makes no model call. |
| `CustomerMemoryTests.factsWithAHighDecayRateStopBeingTrustedFirst` | After 30 days a fact with decay 0.005 keeps 74% of its confidence and a fact with decay 0.05 keeps 5%. |
