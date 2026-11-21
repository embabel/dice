# Backend swap

A memory desk that stores facts about the companies Acme deals with. The same code runs on
DICE's in-memory store, Neo4j, and SQLite. The application starts on the in-memory store.

## Run

```bash
./mvnw -B install -DskipTests
./mvnw -B -f examples/pom.xml -pl examples-backend-swap -am verify
```

The example runs as tests. `BackendSwapApplication` is the Spring Boot configuration they
start. The tests add `ScriptedModel`, a placeholder model with no replies: Embabel Agent will not
start without a default model, and nothing here calls one, so no API key is needed.

`SameCodeEveryBackend` holds the shared assertions. `InMemoryBackendTests`, `Neo4jBackendTests`,
and `SqliteBackendTests` each run them and verify that Spring selected the expected backend.
`StartupTests` checks that the application defaults to in-memory even with a separate Drivine
datasource configured.

- **With Docker running**, all three backend test classes run. `Neo4jBackendTests` starts
  `neo4j:2026.05-community` with Testcontainers, once for the whole test run.
- **Without Docker**, the in-memory and SQLite tests run. Testcontainers skips `Neo4jBackendTests`.

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

### SQLite

The example includes `dice-starter-sqlite`. In `application.properties`, replace the existing
`embabel.dice.store.type=in-memory` line with these settings.

```properties
embabel.dice.store.type=
database.datasources.local.type=SQLITE
database.datasources.local.database-name=./dice.db
```

The blank value lets the starter select the Drivine store. You can also pass
`--embabel.dice.store.type=` and the two datasource settings as command-line arguments.
Drivine opens or creates the SQLite file. If startup fails, check that the directory exists and
the application has write access. The test uses a fresh file in a temporary directory.

## Modules

The application is one package, `example.dice.backends`. `ModularityTests` uses Spring Modulith
to verify the module structure.

| Package | Public types | Package-private |
|---|---|---|
| `backends` | `MemoryDesk` | `BackendSwapApplication`, `GraphStoreConfiguration`, `WordEmbedder` |

## DICE usage

| Step | Where | What to read |
|---|---|---|
| Pick the store | Spring Boot configuration | The example sets `in-memory` by default. For SQLite, clear the override and configure a `SQLITE` datasource. The Neo4j setup selects `graph` explicitly. |
| Store and query facts | `MemoryDesk` | Takes a `PropositionRepository` and nothing else. `remember` saves a fact in context `acme` with a resolved entity mention, `about` queries the active facts that mention an entity, `correct` marks a fact `CONTRADICTED`, and `get` finds one by id. |
| Embed offline | `WordEmbedder` | DICE needs an embedding service to start. This one hashes words into 16 dimensions and stands in for a real model. |
| Connect Neo4j | `GraphStoreConfiguration` | Supplies the `neo` managers when the application explicitly sets the store type to `graph`. |

`MemoryDesk` imports no store class. Swapping the store changes no Java code.

```java
propositions.query(
        PropositionQuery.againstContext(CONTEXT)
                .withStatus(PropositionStatus.ACTIVE)
                .withEntityId(entityId));
```

### The Neo4j configuration

The Neo4j path in this example supplies a `PersistenceManager` and `StatelessGraphObjectManager`
for the datasource `neo`. `GraphStoreConfiguration` declares them and enables Drivine when
`embabel.dice.store.type=graph`. The SQLite setup uses the starter to create its managers.

The connection is configured under `database.datasources.neo`: `host`, `port`, `username`,
`password`, `type=NEO4J` and `database-name`.

### The embedding service

DICE uses the application's `EmbeddingService` bean when it is the only one, and otherwise the
platform's default embedding model. A real provider key registers more embedding services, so
`WordEmbedder` is marked `@Primary` to stay the one DICE picks.

## Behaviour the tests assert

| Test | Behaviour |
|---|---|
| `about` | Three facts about Acme and one about Globex are remembered. `about("acme")` returns exactly the three Acme facts, and `about("globex")` the Globex one. |
| `get` | Returns a fact with its text, confidence and entity mention as they were remembered. |
| `correct` | The fact drops out of `about("acme")`, but `get` still returns it, now `CONTRADICTED`. |
| The store in use | The in-memory tests get `InMemoryPropositionRepository`. Neo4j and SQLite get `DrivinePropositionRepository`. The SQLite test also checks the manager's database type and that it created the file. |

The first three rows run the same test code on all three stores.
