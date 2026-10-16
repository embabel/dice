# FalkorDB storage

`dice-storage-falkordb-starter` selects durable FalkorDB propositions and chunk history through
Drivine 0.1.6. Add it to an application that already supplies Embabel Agent 1.5.3 APIs.
The tested client is Jedis 8.0.1, with the pinned FalkorDB server image used by this module's tests.

## Maven dependencies

A consuming BOM can manage transitive Jedis back to an incompatible version. Declare the tested
version directly in the consuming application (or its dependency management). The starter's
transitive dependency cannot override the consuming BOM. This complete example imports the
Spring Boot and Embabel BOMs and aligns the client explicitly:

```xml
<project xmlns="http://maven.apache.org/POM/4.0.0">
  <modelVersion>4.0.0</modelVersion>
  <groupId>example</groupId>
  <artifactId>falkordb-application</artifactId>
  <version>1.0.0</version>
  <dependencyManagement>
    <dependencies>
      <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-dependencies</artifactId>
        <version>4.1.1</version>
        <type>pom</type>
        <scope>import</scope>
      </dependency>
      <dependency>
        <groupId>com.embabel.agent</groupId>
        <artifactId>embabel-agent-dependencies</artifactId>
        <version>1.5.3</version>
        <type>pom</type>
        <scope>import</scope>
      </dependency>
    </dependencies>
  </dependencyManagement>
  <dependencies>
    <dependency>
      <groupId>com.embabel.dice</groupId>
      <artifactId>dice-storage-falkordb-starter</artifactId>
      <version>0.5.0-SNAPSHOT</version>
    </dependency>
    <dependency>
      <groupId>com.embabel.agent</groupId>
      <artifactId>embabel-agent-api</artifactId>
    </dependency>
    <dependency>
      <groupId>com.embabel.agent</groupId>
      <artifactId>embabel-agent-rag-core</artifactId>
    </dependency>
    <dependency>
      <groupId>redis.clients</groupId>
      <artifactId>jedis</artifactId>
      <version>8.0.1</version>
    </dependency>
  </dependencies>
  <repositories>
    <repository>
      <id>embabel-releases</id>
      <url>https://repo.embabel.com/artifactory/libs-release</url>
      <snapshots><enabled>false</enabled></snapshots>
    </repository>
    <repository>
      <id>embabel-snapshots</id>
      <url>https://repo.embabel.com/artifactory/libs-snapshot</url>
      <releases><enabled>false</enabled></releases>
      <snapshots><enabled>true</enabled></snapshots>
    </repository>
  </repositories>
</project>
```

## Spring configuration

Use the same datasource name in the DICE selector and Drivine configuration. `default` uses
Drivine's first registered datasource; name it explicitly when configuring multiple datasources.
Both the declared database type and the actual provider are validated before creating stores.

```properties
embabel.dice.store.type=falkordb
embabel.dice.store.falkordb.datasource=knowledge
database.datasources.knowledge.type=FALKORDB
database.datasources.knowledge.host=${FALKORDB_HOST}
database.datasources.knowledge.port=${FALKORDB_PORT}
database.datasources.knowledge.database-name=${FALKORDB_GRAPH}
database.datasources.knowledge.cypher-dialect=FALKORDB
```

Configure authentication through Drivine's datasource `password` property using the application's
secret configuration. The graph name is a Redis key, not a Neo4j database name. Server persistence,
backups and authentication remain deployment responsibilities; application context restarts
reuse the same graph. The tests use a disposable server with disk persistence disabled.

No `Ai` bean or embedding model is required. A unique application `EmbeddingService` bean (including
one marked `@Primary`) enables embedding and native exact cosine search. Multiple services with no
primary leave embeddings disabled. The starter does not select an Embabel default model or make
external model calls at startup. Supply an explicitly selected service to enable vectors.
Application `PropositionRepository` and `ChunkHistoryStore` beans each replace that default.
Without `type=falkordb`, the existing in-memory default and `type=graph` Neo4j selection retain
their behavior.

## Using the repository

These examples use the injected repository. The Kotlin function and Java class are compiled and
executed by the starter tests, against a real server. Import the Kotlin types shown below.

```kotlin
import com.embabel.agent.core.ContextId
import com.embabel.dice.proposition.Proposition
import com.embabel.dice.proposition.PropositionQuery
import com.embabel.dice.proposition.PropositionRepository

fun storeFact(repository: PropositionRepository): Proposition {
    val fact = Proposition(
        contextId = ContextId("example"), text = "A durable fact",
        mentions = emptyList(), confidence = 0.9,
    )
    repository.save(fact)
    check(repository.query(PropositionQuery.againstContext("example")).any { it.id == fact.id })
    return fact
}
```

```java
import com.embabel.dice.proposition.Proposition;
import com.embabel.dice.proposition.PropositionQuery;
import com.embabel.dice.proposition.PropositionRepository;
import java.util.List;
import java.time.Instant;
import java.util.UUID;
import com.embabel.dice.proposition.PropositionStatus;

public class FalkorExample {
    public static Proposition storeFact(PropositionRepository repository) {
        var now = Instant.now();
        var fact = Proposition.create(UUID.randomUUID().toString(), "example", "A Java fact",
                List.of(), 0.9, 0.0, null, List.of(), now, now, PropositionStatus.ACTIVE);
        repository.save(fact);
        if (repository.query(PropositionQuery.againstContext("example")).stream()
                .noneMatch(p -> p.getId().equals(fact.getId()))) {
            throw new IllegalStateException("Fact was not persisted");
        }
        return fact;
    }
}
```

## Capabilities and durability

| Surface | FalkorDB starter behavior |
|---|---|
| Propositions, nested metadata, temporal fields, mentions, provenance | Durable aggregate; ID-preserving upserts, appended evidence |
| Processed chunk hashes and source bookmarks | Durable and context-isolated |
| Structured queries, counts, keyword overlap and context-set reads | Native filtering and limiting before aggregate hydration |
| Legacy vector reads and clustering | Native exact cosine over persisted embeddings when a service is supplied; dimension mismatches excluded |
| Strict policy reads and context-set vector capability | Not advertised by this backend |
| Native entity graph traversal (`GraphQueryCapable`) | Not advertised; portable graph facade falls back to proposition reads |
| Decay manager | Portable `InMemoryDecayManager`; computes confidence on read and persists lifecycle changes through the repository |
| Projection and collector audit records | In-memory defaults; lost on restart |
| Collector trace | In-memory when collector auto-configuration is enabled (default), including with trace disabled; no FalkorDB trace store |
| Metamodel version store | In-memory when a declared schema enables governance; no durable drift report or observed-schema source |
| Extraction-run and proposition-run-link stores | Unavailable by default, even with extraction-run opt-in; supply application implementations |
| Neo4j schema catalogs, vector indexes and executor | Not registered for this selector |

Vectors use exact search, not ANN or a Neo4j vector index. Native structured filters include the
read scope, status, entity membership, temporal boundaries, confidence, trust and importance. Query
time confidence uses the requested time and decay rate. Embedding errors abort a save before data
changes; re-embedding reports incomplete work rather than silently mixing incompatible dimensions.

## Readiness and consistency

Construction establishes the fixed unique identities `DiceProposition(id)`,
`DiceChunkHash(context, hash)` and `DiceBookmark(context, source)`. It waits for backing indexes and
constraints to become operational before exposing each store. Repeated initialization reuses
existing operational constraints. Duplicate preexisting keys, failed constraints and a readiness
wait longer than ten seconds fail startup; repair the reported data or server condition before
retrying. This barrier uses the backend's existing typed Drivine schema grammar, independently of
Drivine's optional catalog startup runner.

Each mutation is one atomic server statement; updates use optimistic version checks and bounded
retries. Several calls, batch saves, decay sweeps and proposition-plus-chunk writes do not form a
single transaction. Caller Spring or Drivine transactions are rejected before writes, because the
server cannot provide cross-command rollback. Adding `@Transactional` cannot create that guarantee.
Separate IDs with identical text remain distinct; there is no Neo4j `(contextId, text)` dedup rule.
Persist application completion markers only after all required writes have succeeded.

## Moving existing knowledge

FalkorDB uses its own labels and aggregate representation. Changing the selector does not migrate a
Neo4j graph. Stop source writers, provision an empty destination, and copy through the existing
repository APIs. Hydrate evidence explicitly: a source query may omit it. This compiled and tested
Kotlin function preserves IDs, full proposition fields and complete evidence and checks the result.
It uses the imports from the Kotlin example above.

```kotlin
fun copyKnowledge(source: PropositionRepository, target: PropositionRepository) {
    check(target.count() == 0) { "Use an empty target" }
    val complete = source.findAll().map { proposition ->
        proposition.copy(provenanceEntries = source.provenanceOf(proposition.id))
    }
    complete.forEach { proposition ->
        check(target.save(proposition).id == proposition.id) { "Source ID changed" }
        check(target.findById(proposition.id) == proposition) { "Proposition fields changed" }
        check(target.provenanceOf(proposition.id).toSet() == proposition.provenanceEntries.toSet()) {
            "Evidence changed"
        }
    }
    check(target.count() == complete.size) { "Destination count differs" }
}
```

Keep both stores quiescent throughout the copy and verification, and keep the source until readers
have switched successfully. The copy is a series of independent writes; a failure can leave a
partial destination. Diagnose it and restart with an empty destination. Chunk hashes/bookmarks,
projection and collector audits, traces, metamodel versions and extraction-run records are not
copied by this function. Retain or replay that companion data separately where needed.
