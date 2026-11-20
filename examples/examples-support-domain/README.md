# Support Domain

A support desk that remembers what its customers say in chat. Each chat goes through DICE once.
The facts it gives are stored with the chat as their source, and the customers, contacts and
plans it names become entities in a small graph. A name in a later chat resolves to the entity
an earlier chat created.

The desk serves one account, Acme, so every fact is stored under the context `acme`.

## Run

```bash
./mvnw -B -f examples/pom.xml -pl examples-support-domain -am verify
```

The example runs as tests. `SupportDomainApplication` is the Spring Boot configuration they
start. `SupportDesk.chat(chatId, text)` is the only entry point they call.

## DICE usage

| Step | Where | What to read |
|---|---|---|
| Define a schema | `SupportSchema.supportDictionary` | `Customer`, `Contact` and `Plan` passed to `DataDictionary.fromClasses`. A `Contact` has a name and a role, so Dana the admin is a `Contact` whose role is admin. |
| Build the pipeline | `SupportMemory.propositionPipeline` | `PropositionPipeline` with an LLM extractor, an event listener, and ungrounded mentions dropped. |
| Handle pipeline events | `SupportMemory.diceEvents` | DICE events are republished as Spring events, so any bean can handle one with `@EventListener`. |
| Keep facts and entities | `SupportMemory.propositionRepository`, `SupportMemory.supportEntities` | An in-memory fact store and an in-memory entity store, one of each for the account. |
| Resolve entities | `SupportMemory.supportResolver` | `EscalatingEntityResolver` looks mentions up by name in the entity store, so "Acme" in chat 2 is the entity chat 1 created. |
| Store as one unit | `SupportMemory.supportWriter`, `SupportDesk.chat` | `processText` returns unsaved results. `PersistUnitWriter.inMemory` stores the facts and new entities as one `PersistUnit` and puts both stores back when a step fails. |
| Keep the source | `SupportDesk.chat` | The chat is the `SourceLocator`, a `ConnectorRef` named `chat`, and its id is the chunk id, so every fact points back at the chat it came from. |
| Project the graph | `TwoChatTests.projectsTheChatsToEdgesThatCiteTheirStoredFacts` | `RelationBasedGraphProjector` turns the stored facts into edges over the resolved entities. Each edge carries its confidence and the fact id used to find the source chat. |

`PersistUnit` and `PersistUnitWriter` are marked experimental in DICE.

## Behaviour the tests assert

| Test | Behaviour |
|---|---|
| `ModularityTests.verifiesModuleStructure` | Spring Modulith finds no dependency cycle and no use of another module's internals. |
| `TwoChatTests.printsEveryStoredFactAndItsConfidenceFadesOverThirtyDays` | Each stored fact is printed with its confidence, importance and decay, and its effective confidence 30 days on is lower than today. |
| `TwoChatTests.theFirstChatTagsTheCustomerAndThePlan` | Chat 1, the customer saying Acme is on Pro, gives facts that mention a `Customer` and a `Plan`. |
| `TwoChatTests.theSecondChatTagsTheContactAndThePlan` | Chat 2, admin Dana saying Acme moved back to Basic, gives facts that mention a `Contact` and a `Plan`. |
| `TwoChatTests.acmeIsTheSameEntityInBothChats` | "Acme" in both chats resolves to the same entity id. |
| `TwoChatTests.everyFactNamesItsChatInProvenanceAndGrounding` | Every fact names the chat it came from, in its provenance and in its grounding. |
| `TwoChatTests.projectsTheChatsToEdgesThatCiteTheirStoredFacts` | Dana and both plans connect to the same Acme entity. Each edge keeps the recorded confidence and links through its fact to the right chat. |
| `TwoChatTests.keepsTheScoresTheModelGaveEachFact` | Confidence, importance and decay are the values the model gave. |
| `TwoChatTests.storesTheProAndTheBasicClaimsSideBySide` | With no reviser, the Pro claim and the Basic claim are both kept. The corrections-history example shows revision. |
| `TwoChatTests.sendsEachChatToTheScriptedModelOnce` | Each chat reaches the scripted model once; no real model is called. |
| `VocabularyTests.theModelIsShownNoSupportTypes` | With a schema that names no types, the prompt lists none of Customer, Contact or Plan. |
| `VocabularyTests.theFactIsStillStored` | The fact is still stored without a schema. |
| `VocabularyTests.theMentionsCarryNoneOfTheSupportTypes` | Without the schema, its mentions carry none of the three support types. |
