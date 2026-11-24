# Backend swap

A memory desk that stores facts about the companies Acme deals with. The same code runs on
DICE's in-memory store, Neo4j, and SQLite. With no datasource configured, it starts in memory.

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

- **With Docker running**, all three backend test classes run. `Neo4jBackendTests` starts
  `neo4j:2026.05-community` with Testcontainers, once for the whole test run.
- **Without Docker**, the in-memory and SQLite tests run. Testcontainers skips `Neo4jBackendTests`.

### Neo4j in your app

Add these settings to your application's `application.properties`. The tests configure
their own datasource.

```properties
database.datasources.neo.type=NEO4J
database.datasources.neo.host=localhost
database.datasources.neo.port=7687
database.datasources.neo.username=neo4j
database.datasources.neo.password=<your password>
database.datasources.neo.database-name=neo4j
```

### SQLite in your app

Add these settings to your application's `application.properties`.

```properties
database.datasources.local.type=SQLITE
database.datasources.local.database-name=./dice.db
```

Drivine opens or creates the SQLite file. The directory must exist and be writable.
The tests use a file in a temporary directory.

### Record an accepted plan change

[WorldProgressionTests](src/test/java/example/dice/backends/WorldProgressionTests.java) runs a
billing event against a fresh SQLite file. Billing accepts Acme's move from Pro to Basic and
supplies the event ID and effective instant. DICE stores both plan periods. The previous
period ends at the same instant the new one begins.

| Read | Result |
|---|---|
| Effective time just before the change | Pro |
| Effective time at the change | Basic |
| Stored history | Pro is `SUPERSEDED`. Basic is `ACTIVE` and links back to Pro. |
| Repeat delivery | The same Basic occurrence is returned. |

The test opts in with `embabel.dice.store.world-progression.enabled=true`, registers the
audit schema for the SQLite datasource, and enables the writer after schema startup.
Call `enable` once on each instance before accepting writes. For an existing database, configure
every instance, back up the file, and stop writers before migration. The migration removes the
legacy exact-text constraint. Restore the backup to roll back to an older release.

```java
var audit = new DrivineAuditLogStore(persistenceManager, transactionManager);
var progression = SqliteWorldProgression.enable(repository, audit);
```

When billing confirms the change, pass the ID of that event. Multiple changes on the same
subscription each have their own event ID.

```java
var basic = progression.record("plan-change-881", pro, nextPlan, changedAt);
```

Open a scoped repository view for the account and check validity intervals to answer which
plan was in effect. Use a complete audit baseline and the audit timestamp to answer what the
application had recorded. In the test, Basic is in effect on March 1. An audit read for March 1
refuses the request because it predates the baseline. A read at the event's recorded time
returns both stored occurrences.

Run just this scenario without Docker or an API key.

```bash
./mvnw -B -f examples/pom.xml -pl examples-backend-swap -am \
  -Dtest=WorldProgressionTests -Dsurefire.failIfNoSpecifiedTests=false test
```

Use the [audit history guide](../../dice-user-guide/src/main/asciidoc/features/audit-history.adoc#features-world-progression)
for setup and migration. See [Corrections and History](../examples-corrections-history/README.md)
for how the ordinary revision pipeline handles a source disagreement.

## Modules

The application is one package, `example.dice.backends`. `ModularityTests` uses Spring Modulith
to verify the module structure.

| Package | Public types | Package-private |
|---|---|---|
| `backends` | `MemoryDesk` | `BackendSwapApplication`, `WordEmbedder` |

## DICE usage

| Step | Where | What to read |
|---|---|---|
| Pick the store | Spring Boot configuration | The starter uses Drivine when a datasource is configured. Otherwise DICE uses the in-memory store. |
| Store and query facts | `MemoryDesk` | Takes a `PropositionRepository` and nothing else. `remember` saves a fact in context `acme` with a resolved entity mention, `about` queries the active facts that mention an entity, `correct` marks a fact `CONTRADICTED`, and `get` finds one by id. |
| Embed offline | `WordEmbedder` | DICE needs an embedding service to start. This one hashes words into 16 dimensions and stands in for a real model. |

`MemoryDesk` imports no store class. Swapping the store changes no Java code.

```java
propositions.query(
        PropositionQuery.againstContext(CONTEXT)
                .withStatus(PropositionStatus.ACTIVE)
                .withEntityId(entityId));
```

### Starter selection

This example uses `dice-starter-sqlite` so the same build can run all three test cases.
In your app, use `dice-starter-sqlite` for SQLite or `dice-starter-neo4j` for Neo4j.
The starter creates the Drivine managers for the configured datasource. If the app has
several datasources, name the one DICE should use with `embabel.dice.store.datasource`.

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
