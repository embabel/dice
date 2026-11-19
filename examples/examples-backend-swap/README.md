# Backend swap

A memory desk that stores facts about the companies Acme deals with. The same code runs on
DICE's in-memory store and on Neo4j. One property picks the store:

```properties
embabel.dice.store.type=in-memory
```

Set it to `graph` and the facts go to Neo4j instead.

## Run

```bash
./mvnw -B -f examples/pom.xml -pl examples-backend-swap -am verify
```

The example runs as tests. `BackendSwapApplication` is the Spring Boot configuration they
start. The tests add `ScriptedModel`, a placeholder model with no replies: Embabel Agent will not
start without a default model, and nothing here calls one, so no API key is needed.

`SameCodeEveryBackend` holds the assertions. `InMemoryBackendTests` runs them on the in-memory
store and `Neo4jBackendTests` runs them on Neo4j. Each subclass only adds a check that it got
the store it asked for. `StartupTests` starts the application as configured, on the
in-memory store.

- **With Docker running**, both classes run. `Neo4jBackendTests` starts
  `neo4j:2026.05-community` with Testcontainers, once for the whole test run.
- **Without Docker**, `Neo4jBackendTests` is skipped, not failed, and the in-memory tests still
  run.

### Pointing at a real Neo4j

Set the store type to `graph` and give the `neo` datasource its connection, in
`application.properties` or as `--` arguments:

```properties
embabel.dice.store.type=graph
database.datasources.neo.type=NEO4J
database.datasources.neo.host=localhost
database.datasources.neo.port=7687
database.datasources.neo.username=neo4j
database.datasources.neo.password=<your password>
database.datasources.neo.database-name=neo4j
```

`MemoryDesk` does not change.

### SQLite

A SQLite store is coming with #220. Once it lands, it becomes a third value of
`embabel.dice.store.type` and a third subclass of `SameCodeEveryBackend`.

## Modules

The application is one package, `example.dice.backends`. `ModularityTests` uses Spring Modulith
to verify the module structure.

| Package | Public types | Package-private |
|---|---|---|
| `backends` | `MemoryDesk` | `BackendSwapApplication`, `GraphStoreConfiguration`, `WordEmbedder` |

## DICE usage

| Step | Where | What to read |
|---|---|---|
| Pick the store | `application.properties` | `embabel.dice.store.type` is `in-memory` or `graph`. `dice-storage-autoconfigure` builds the matching `PropositionRepository`. |
| Store and query facts | `MemoryDesk` | Takes a `PropositionRepository` and nothing else. `remember` saves a fact in context `acme` with a resolved entity mention, `about` queries the active facts that mention an entity, `correct` marks a fact `CONTRADICTED`, and `get` finds one by id. |
| Embed offline | `WordEmbedder` | Both stores need an embedding service to start. This one hashes words into 16 dimensions and stands in for a real model. |
| Connect Neo4j | `GraphStoreConfiguration` | The one piece the graph store adds. It loads only when the type is `graph`. |

`MemoryDesk` imports no store class. Swapping the store changes no Java code.

```java
propositions.query(
        PropositionQuery.againstContext(CONTEXT)
                .withStatus(PropositionStatus.ACTIVE)
                .withEntityId(entityId));
```

### The graph store

DICE builds the Neo4j store itself, but it needs two Drivine beans that the application
supplies, not the autoconfiguration: a `PersistenceManager` and a `StatelessGraphObjectManager`,
both for the datasource `neo`. `GraphStoreConfiguration` declares them and switches Drivine on
with `@EnableDrivine`. It is conditional on `embabel.dice.store.type=graph`, because on the
in-memory store Drivine is not set up and the beans cannot be built.

The connection is configured under `database.datasources.neo`: `host`, `port`, `username`,
`password`, `type=NEO4J` and `database-name`.

### The embedding service

DICE uses the application's `EmbeddingService` bean when it is the only one, and otherwise the
platform's default embedding model. A real provider key registers more embedding services, so
`WordEmbedder` is marked `@Primary` to stay the one DICE picks.

The property switches the stores `dice-storage-autoconfigure` builds, the proposition store
among them. A store the application builds itself does not follow it.

## Behaviour the tests assert

| Test | Behaviour |
|---|---|
| `about` | Three facts about Acme and one about Globex are remembered. `about("acme")` returns exactly the three Acme facts, and `about("globex")` the Globex one. |
| `get` | Returns a fact with its text, confidence and entity mention as they were remembered. |
| `correct` | The fact drops out of `about("acme")`, but `get` still returns it, now `CONTRADICTED`. |
| The store in use | `InMemoryBackendTests` gets `InMemoryPropositionRepository`, `Neo4jBackendTests` gets `DrivinePropositionRepository`. |

The first three rows are the same test code on both stores, and pass on both.
