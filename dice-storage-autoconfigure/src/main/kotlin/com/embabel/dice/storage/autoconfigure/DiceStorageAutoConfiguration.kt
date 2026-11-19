/*
 * Copyright 2024-2026 Embabel Pty Ltd.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.embabel.dice.storage.autoconfigure

import com.embabel.agent.api.common.Ai
import com.embabel.common.ai.model.EmbeddingService
import com.embabel.dice.spi.DecayStatusPolicy
import com.embabel.dice.incremental.ChunkHistoryStore
import com.embabel.dice.incremental.InMemoryChunkHistoryStore
import com.embabel.dice.projection.lineage.CollectorRecordStore
import com.embabel.dice.projection.lineage.InMemoryCollectorRecordStore
import com.embabel.dice.projection.lineage.InMemoryProjectionRecordStore
import com.embabel.dice.projection.lineage.ProjectionRecordStore
import com.embabel.dice.proposition.DecayManager
import com.embabel.dice.proposition.DecaySweepConfig
import com.embabel.dice.proposition.PropositionRepository
import com.embabel.dice.proposition.extraction.ExtractionRunStore
import com.embabel.dice.proposition.extraction.PropositionRunLinkStore
import com.embabel.dice.proposition.store.InMemoryDecayManager
import com.embabel.dice.proposition.store.InMemoryPropositionRepository
import com.embabel.dice.storage.DiceStorageSchema
import com.embabel.dice.storage.DrivineChunkHistoryStore
import com.embabel.dice.storage.DrivineCollectorRecordStore
import com.embabel.dice.storage.DrivineExtractionRunStore
import com.embabel.dice.storage.DrivinePropositionRepository
import com.embabel.dice.storage.DrivinePropositionRunLinkStore
import com.embabel.dice.storage.DrivineProjectionRecordStore
import com.embabel.dice.storage.ExtractionRunSchema
import com.embabel.dice.storage.GraphDecayManager
import com.embabel.dice.storage.LineageSchema
import com.embabel.dice.storage.diceStorageCatalog
import org.drivine.connection.DatabaseRegistry
import org.drivine.connection.DatabaseType
import org.drivine.connection.NativeDriverSource
import org.drivine.manager.StatelessGraphObjectManager
import org.drivine.manager.DelegatingPersistenceManager
import org.drivine.manager.PersistenceManager
import org.drivine.schema.RangeIndexSpec
import org.drivine.schema.SchemaCatalog
import org.drivine.schema.SimilarityFunction
import org.drivine.schema.UniquenessConstraintSpec
import org.drivine.schema.VectorIndexSpec
import org.drivine.transaction.DrivineTransactionManager
import org.drivine.transaction.TransactionContextHolder
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.ObjectProvider
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.scheduling.annotation.EnableScheduling
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.ResourceTransactionManager
import java.nio.file.Files
import java.nio.file.Path
import javax.sql.DataSource

/**
 * Auto-configures the Dice proposition store and its lineage record stores (projection records and
 * the collector audit trail).
 *
 * `embabel.dice.store.type=graph` selects the Drivine/Neo4j backend; anything else (default) uses the
 * in-memory backend. Every bean is `@ConditionalOnMissingBean`, so an application's own bean always
 * wins. Graph beans are declared before their in-memory counterparts so the flip resolves by
 * registration order.
 *
 * Schema (indexes/constraints) is declared as [SchemaCatalog] beans; Drivine's `SchemaManager`
 * (registered by the starter) applies them idempotently on startup — no runner here.
 */
@AutoConfiguration
@EnableConfigurationProperties(DiceStoreProperties::class)
class DiceStorageAutoConfiguration {

    private val logger = LoggerFactory.getLogger(DiceStorageAutoConfiguration::class.java)

    /** Drivine's transaction state, when Drivine is configured: see [drivineTransactions]. */
    @Autowired(required = false)
    private var transactionContextHolder: TransactionContextHolder? = null

    /** Drivine's datasources, when Drivine is configured: see [requireLockingSqliteTransactions]. */
    @Autowired(required = false)
    private var databaseRegistry: DatabaseRegistry? = null

    /**
     * The transaction manager the graph stores run their own transactions with: Drivine's.
     *
     * Drivine registers its manager only when the context has none, so a host that brings one for
     * its own database (JPA, JDBC) leaves Drivine without. The stores were then given the host's,
     * whose transactions Drivine's statements do not join: each statement committed on its own,
     * and a save's find-then-insert stopped being atomic. Given any manager but Drivine's, this
     * makes Drivine's over the same transaction state, so a store's transactions and a caller's
     * Drivine transactions still join each other.
     *
     * One case keeps the host's manager: Drivine reading the very data source the manager manages
     * through Spring's `TransactionAwareDataSourceProxy`, when the manager's transactions bind a
     * JDBC connection for it. A JDBC manager's always do; a JPA manager's do when its dialect
     * exposes the JDBC connection, as Hibernate's does. Inside a host transaction Drivine's
     * statements then run on the host's connection and are the host's to commit, and a Drivine
     * transaction of the store's own on that connection would commit the host's work with it: Drivine
     * commits its own transaction on whatever connection it is handed, without asking whether a
     * Spring transaction holds it, as it does ask before it turns on auto-commit. Keeping the host's
     * manager covers that gap in Drivine until Drivine joins or refuses such a connection itself. A JPA
     * manager whose dialect binds no connection is refused behind the proxy, and any other way of
     * sharing one SQLite file with the host's manager is refused; see [sharingWithHost].
     *
     * Drivine's transaction state is taken from its bean, or else from Drivine's own persistence
     * manager. With neither, the host's manager is used and a warning says so.
     */
    private fun drivineTransactions(
        given: PlatformTransactionManager,
        persistenceManager: PersistenceManager,
    ): PlatformTransactionManager {
        if (given is DrivineTransactionManager) return given
        when (sharingWithHost(given, persistenceManager)) {
            HostSharing.THROUGH_PROXY -> {
                check(bindsJdbcConnection(given)) {
                    "Transaction manager ${given.javaClass.name} manages the data source the graph store's " +
                        "datasource '${persistenceManager.database}' reads through a TransactionAwareDataSourceProxy, " +
                        "but its JPA dialect binds no JDBC connection, so Drivine would write on a connection of its " +
                        "own outside the manager's transactions. Use a JPA dialect that exposes the JDBC " +
                        "connection, such as Hibernate's."
                }
                return given
            }
            HostSharing.SAME_SQLITE_FILE -> error(
                "The graph store's SQLite datasource '${persistenceManager.database}' and transaction manager " +
                    "${given.javaClass.name} use the same SQLite file on connections of their own. SQLite has " +
                    "one write lock per file, so a store write inside the manager's transaction would wait " +
                    "for that transaction's own lock. Give Drivine a TransactionAwareDataSourceProxy over the " +
                    "data source the manager manages, so both use one connection.",
            )
            HostSharing.NONE -> Unit
        }
        // The persistence manager's own state first: it is the one its statements consult.
        val holder = (persistenceManager as? DelegatingPersistenceManager)?.contextHolder ?: transactionContextHolder
        holder?.let { return DrivineTransactionManager(it) }
        logger.warn(
            "The graph stores were given transaction manager {}, which is not Drivine's, and there is no " +
                "Drivine transaction state to make Drivine's from. Their statements will not join its " +
                "transactions. Publish Drivine's TransactionContextHolder as a bean.",
            given.javaClass.name,
        )
        return given
    }

    /** How a host's transaction manager shares the store's data, if it does. */
    private enum class HostSharing { NONE, THROUGH_PROXY, SAME_SQLITE_FILE }

    /**
     * Whether [manager] manages the data source Drivine reads [persistenceManager]'s store through,
     * and how. Through a transaction-aware proxy is the one supported way. On SQLite, the same data
     * source without the proxy, or another data source over the same file, is a second connection
     * to one write lock.
     */
    private fun sharingWithHost(manager: PlatformTransactionManager, persistenceManager: PersistenceManager): HostSharing {
        val managed = managedDataSource(manager) ?: return HostSharing.NONE
        val drivine = drivineDataSource(persistenceManager) ?: return HostSharing.NONE
        val chain = unwrapped(drivine)
        // The proxy looks the host's connection up under its own target, so only the data source it
        // wraps directly can be the one the manager binds.
        if (isTransactionAwareProxy(chain.first()) && chain.getOrNull(1) == managed) return HostSharing.THROUGH_PROXY
        if (persistenceManager.type != DatabaseType.SQLITE) return HostSharing.NONE
        if (managed in chain) return HostSharing.SAME_SQLITE_FILE
        val drivineFile = sqliteFile(chain.last()) ?: return HostSharing.NONE
        val hostFile = sqliteFile(managed) ?: return HostSharing.NONE
        // Same file, even through a hard link.
        return if (Files.isSameFile(drivineFile, hostFile)) HostSharing.SAME_SQLITE_FILE else HostSharing.NONE
    }

    /**
     * Whether [manager]'s transactions bind a JDBC connection for its data source, so a proxy over
     * that data source hands it out. A JDBC manager always does. A JPA manager does only when its
     * dialect exposes the JDBC connection: Hibernate's and EclipseLink's do, Spring's default dialect
     * does not. Only a live transaction shows the handle itself, so at startup this can see only
     * that the dialect overrides `getJdbcConnection`. A custom dialect has to return a real handle
     * from it; one that returns null passes this check and leaves Drivine on a connection of its own.
     */
    private fun bindsJdbcConnection(manager: PlatformTransactionManager): Boolean {
        val dialect = runCatching { manager.javaClass.getMethod("getJpaDialect").invoke(manager) }.getOrNull() ?: return true
        val exposing = dialect.javaClass.methods.firstOrNull { it.name == "getJdbcConnection" } ?: return false
        return exposing.declaringClass.name != DEFAULT_JPA_DIALECT
    }

    /** Spring's JPA dialect that binds no JDBC connection, named rather than typed: spring-orm is the host's. */
    private val DEFAULT_JPA_DIALECT = "org.springframework.orm.jpa.DefaultJpaDialect"

    /**
     * The JDBC data source [manager] binds its connections under. A JDBC manager names it as its
     * resource. A JPA manager names its entity manager factory there, and gives its data source
     * through `getDataSource()`.
     */
    private fun managedDataSource(manager: PlatformTransactionManager): DataSource? {
        val resource = runCatching { (manager as? ResourceTransactionManager)?.resourceFactory }.getOrNull()
        return resource as? DataSource
            ?: runCatching { manager.javaClass.getMethod("getDataSource").invoke(manager) as? DataSource }.getOrNull()
    }

    /** Whether [dataSource] is Spring's transaction-aware proxy, which hands out a transaction's bound connection. */
    private fun isTransactionAwareProxy(dataSource: DataSource): Boolean =
        generateSequence<Class<*>>(dataSource.javaClass) { it.superclass }
            .any { it.name == TRANSACTION_AWARE_PROXY }

    /**
     * The SQLite file [dataSource] opens, as SQLite itself names it, or null when it is not SQLite's
     * or cannot be asked. SQLite resolves `file:` URIs and their escapes, so two spellings of one
     * file come out the same.
     */
    private fun sqliteFile(dataSource: DataSource): Path? = runCatching {
        val file = dataSource.connection.use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery("PRAGMA database_list").use { rows ->
                    generateSequence { if (rows.next()) rows.getString("name") to rows.getString("file") else null }
                        .firstOrNull { it.first == "main" }?.second
                }
            }
        }
        file?.takeIf { it.isNotEmpty() }?.let { Path.of(it).toRealPath() }
    }.getOrNull()

    /** How far [unwrapped] follows delegating data sources, so a cycle cannot loop for ever. */
    private val maxDataSourceWrappers = 8

    /** Spring's transaction-aware proxy, named rather than typed: spring-jdbc is the host's to bring. */
    private val TRANSACTION_AWARE_PROXY = "org.springframework.jdbc.datasource.TransactionAwareDataSourceProxy"

    /**
     * [dataSource] and each data source it delegates to, outermost first. Spring's delegating data
     * sources, the transaction-aware proxy among them, name theirs `targetDataSource`.
     */
    private fun unwrapped(dataSource: DataSource): List<DataSource> =
        generateSequence(dataSource) { current ->
            runCatching { current.javaClass.getMethod("getTargetDataSource").invoke(current) as? DataSource }.getOrNull()
        }.take(maxDataSourceWrappers).toList()

    /**
     * The JDBC data source behind [persistenceManager]'s store, when Drivine reaches it through one.
     * The registry is the one Drivine's own persistence manager carries, which its statements use,
     * or else Drivine's bean.
     */
    private fun drivineDataSource(persistenceManager: PersistenceManager): DataSource? {
        val registry = (persistenceManager as? DelegatingPersistenceManager)?.contextHolder?.databaseRegistry ?: databaseRegistry
        val provider = registry?.connectionProvider(persistenceManager.database) as? NativeDriverSource<*>
        return provider?.nativeDriver as? DataSource
    }

    /**
     * Refuses a SQLite store whose transactions do not take the write lock as they begin, before a
     * save can fail on it, and one whose transaction mode cannot be read at all. See
     * [SqliteTransactionMode].
     */
    private fun requireLockingSqliteTransactions(persistenceManager: PersistenceManager) {
        if (persistenceManager.type != DatabaseType.SQLITE) return
        val name = persistenceManager.database
        val dataSource = checkNotNull(drivineDataSource(persistenceManager)) {
            "SQLite datasource '$name' gives no JDBC data source to read its transaction mode from. DICE needs " +
                "its transactions to take the write lock as they begin: use Drivine's own SQLite provider."
        }
        SqliteTransactionMode.requireImmediate(dataSource, name)
    }

    // ---- Graph backend (embabel.dice.store.type=graph) ----

    @Bean
    @ConditionalOnBean(Ai::class)
    @ConditionalOnProperty(prefix = "embabel.dice.store", name = ["type"], havingValue = "graph")
    @ConditionalOnMissingBean(PropositionRepository::class)
    fun drivinePropositionRepository(
        graphObjectManager: StatelessGraphObjectManager,
        persistenceManager: PersistenceManager,
        ai: Ai,
        transactionManager: PlatformTransactionManager,
        embeddingServices: ObjectProvider<EmbeddingService>,
    ): PropositionRepository {
        logger.info(
            "Wiring graph proposition store (Drivine/Neo4j), vector index '{}'",
            DrivinePropositionRepository.VECTOR_INDEX,
        )
        requireLockingSqliteTransactions(persistenceManager)
        return DrivinePropositionRepository(
            graphObjectManager,
            persistenceManager,
            storeEmbeddingService("graph", ai, embeddingServices),
            drivineTransactions(transactionManager, persistenceManager),
        )
    }

    /**
     * The application's own [EmbeddingService] bean where there is an unambiguous one
     * (a `@Primary` bean counts), otherwise the platform default.
     *
     * Preferring the bean matters for a host that can start with NO embedding model
     * configured — one whose provider key arrives at first run rather than at boot.
     * Such a host registers an embedding service that reports its own absence and can be
     * switched on later, whereas `ai.withDefaultEmbeddingService()` resolves the default
     * eagerly and throws when no model is registered, taking the context down with it.
     * [DrivinePropositionRepository] only touches the service when it actually embeds, so
     * an absent-tolerant one is safe to hold.
     *
     * Several beans and no `@Primary` is the ORDINARY case, not an ambiguity to fail on: the platform
     * registers one bean per model it can reach (an OpenAI key alone registers three), and choosing
     * among those is exactly what the configured default embedding model is for. A host that wants
     * its own service used marks it `@Primary`.
     */
    private fun embeddingService(ai: Ai, embeddingServices: ObjectProvider<EmbeddingService>): EmbeddingService =
        embeddingServices.getIfUnique() ?: ai.withDefaultEmbeddingService()

    /**
     * [embeddingService] for a proposition store, saying which one it got and why. Both stores go
     * through here, so a host's embedding service reaches whichever backend it runs, and the one
     * line an operator needs — is memory embedded with the model I configured? — is at startup
     * rather than inferred from a vector-width mismatch later.
     */
    private fun storeEmbeddingService(
        store: String,
        ai: Ai,
        embeddingServices: ObjectProvider<EmbeddingService>,
    ): EmbeddingService {
        val hostService = embeddingServices.getIfUnique()
        val service = hostService ?: ai.withDefaultEmbeddingService()
        logger.info(
            "{} proposition store embeds with '{}' ({}): {}",
            store,
            service.name,
            service.provider,
            if (hostService != null) "the application's EmbeddingService bean" else "the platform default embedding service",
        )
        return service
    }

    @Bean
    @ConditionalOnProperty(prefix = "embabel.dice.store", name = ["type"], havingValue = "graph")
    @ConditionalOnMissingBean(ChunkHistoryStore::class)
    fun drivineChunkHistoryStore(
        graphObjectManager: StatelessGraphObjectManager,
        persistenceManager: PersistenceManager,
    ): ChunkHistoryStore = DrivineChunkHistoryStore(graphObjectManager, persistenceManager)

    @Bean
    @ConditionalOnBean(PropositionRepository::class)
    @ConditionalOnProperty(prefix = "embabel.dice.store", name = ["type"], havingValue = "graph")
    @ConditionalOnMissingBean(DecayManager::class)
    fun graphDecayManager(
        repository: PropositionRepository,
        persistenceManager: PersistenceManager,
    ): DecayManager = GraphDecayManager(repository, persistenceManager)

    @Bean
    @ConditionalOnProperty(prefix = "embabel.dice.store", name = ["type"], havingValue = "graph")
    @ConditionalOnMissingBean(ProjectionRecordStore::class)
    fun drivineProjectionRecordStore(
        persistenceManager: PersistenceManager,
    ): ProjectionRecordStore = DrivineProjectionRecordStore(persistenceManager)

    @Bean
    @ConditionalOnProperty(prefix = "embabel.dice.store", name = ["type"], havingValue = "graph")
    @ConditionalOnMissingBean(CollectorRecordStore::class)
    fun drivineCollectorRecordStore(
        persistenceManager: PersistenceManager,
    ): CollectorRecordStore = DrivineCollectorRecordStore(persistenceManager)

    /**
     * The durable extraction-run header store.
     *
     * Two conditions, both required. The graph backend has to be selected
     * (`embabel.dice.store.type=graph`), and extraction runs have to be turned on with
     * `embabel.dice.extraction.runs.enabled=true`. The property defaults to off, so an upgrading
     * host gets none of this until it asks.
     *
     * The property is what carries the decision, because registering these beans is what puts the
     * run schema in front of Drivine's schema manager, and that writes constraints and indexes to
     * the host's database on startup. [extractionRunSchema] lists exactly what lands. A host that
     * turns the flag on has consented to those writes; one that leaves it alone keeps a database
     * with no run labels in it.
     */
    @Bean
    @ConditionalOnProperty(prefix = "embabel.dice.store", name = ["type"], havingValue = "graph")
    @ConditionalOnProperty(
        prefix = "embabel.dice.extraction.runs",
        name = ["enabled"],
        havingValue = "true",
        matchIfMissing = false,
    )
    @ConditionalOnMissingBean(ExtractionRunStore::class)
    fun drivineExtractionRunStore(
        persistenceManager: PersistenceManager,
        transactionManager: PlatformTransactionManager,
    ): ExtractionRunStore {
        requireLockingSqliteTransactions(persistenceManager)
        return DrivineExtractionRunStore(persistenceManager, drivineTransactions(transactionManager, persistenceManager))
    }

    /**
     * Where `(proposition, run)` links go.
     *
     * Registered on the same two conditions as the run store: the graph backend, and
     * `embabel.dice.extraction.runs.enabled=true`. It is reached only by an extraction that carries
     * a run, and binding it is still the host's move: `IncrementalPropositionExtraction` takes it
     * through `withRunLineage`, so having the bean in the context does not by itself make anything
     * record lineage.
     */
    @Bean
    @ConditionalOnProperty(prefix = "embabel.dice.store", name = ["type"], havingValue = "graph")
    @ConditionalOnProperty(
        prefix = "embabel.dice.extraction.runs",
        name = ["enabled"],
        havingValue = "true",
        matchIfMissing = false,
    )
    @ConditionalOnMissingBean(PropositionRunLinkStore::class)
    fun drivinePropositionRunLinkStore(
        persistenceManager: PersistenceManager,
    ): PropositionRunLinkStore = DrivinePropositionRunLinkStore(persistenceManager)

    /**
     * The constraints and indexes the run store and the lineage relation need.
     *
     * Separate from [lineageRecordSchema] because that one is the projection and collector audit
     * trail, which has its own labels and its own lifecycle. Both are ensured on startup by
     * Drivine's schema manager.
     *
     * Behind the same `embabel.dice.extraction.runs.enabled` flag as the two stores, and for the
     * reason the flag exists: this catalog is the part that writes to the host's database. Turning
     * the flag on ensures three uniqueness constraints — on `ExtractionRun(contextId, runId)`,
     * `ExtractionRunInvocation(contextId, runId, invocationIndex, attempt)` and
     * `ExtractionRunTerminalWrite(contextId, runId)` — and five range indexes, four on
     * `ExtractionRun` (`contextId`; `contextId, rootRunId`; `contextId, parentRunId`;
     * `contextId, startedAtEpochSecond`) and one on `ExtractionRunInvocation(contextId, runId)`.
     * Leaving it off keeps every one of them out of the catalog the schema manager sees.
     */
    @Bean
    @ConditionalOnProperty(prefix = "embabel.dice.store", name = ["type"], havingValue = "graph")
    @ConditionalOnProperty(
        prefix = "embabel.dice.extraction.runs",
        name = ["enabled"],
        havingValue = "true",
        matchIfMissing = false,
    )
    fun extractionRunSchema(): SchemaCatalog = SchemaCatalog.of(ExtractionRunSchema.specs())

    /**
     * The lineage stores' schema, registered as a [DiceStorageSchema] so one bean answers both
     * questions about it: Drivine ensures its constraints and indexes, and dice's drift observation
     * reads the same object to recognise `(:ProjectionRecord)` and `(:CollectorRecord)` nodes as its
     * own. Registering the DDL by hand here, as this used to, left the second half to a separate
     * list that could fall behind.
     *
     * Natural keys back the MERGE upserts, so a replayed record updates in place and never
     * duplicates; the range indexes back the lineage lookups. Both live in [LineageSchema].
     */
    @Bean
    @ConditionalOnProperty(prefix = "embabel.dice.store", name = ["type"], havingValue = "graph")
    fun lineageStorageSchema(): DiceStorageSchema = LineageSchema

    @Bean
    @ConditionalOnProperty(prefix = "embabel.dice.store", name = ["type"], havingValue = "graph")
    fun lineageRecordSchema(): SchemaCatalog = diceStorageCatalog(listOf(LineageSchema))

    @Bean
    @ConditionalOnBean(Ai::class)
    @ConditionalOnProperty(prefix = "embabel.dice.store", name = ["type"], havingValue = "graph")
    fun propositionConstraintSchema(): SchemaCatalog = SchemaCatalog.of(
        UniquenessConstraintSpec(label = "Proposition", property = "id"),
        UniquenessConstraintSpec(label = "Mention", property = "id"),
        UniquenessConstraintSpec(label = "ProcessedChunk", property = "id"),
        UniquenessConstraintSpec(label = "Source", property = "key"),
        // Cross-instance dedup backstop: the same fact minted by parallel writers as distinct ids
        // collapses to one node; save() catches the violation and reuses the existing node.
        UniquenessConstraintSpec(label = "Proposition", properties = listOf("contextId", "text")),
        RangeIndexSpec("Proposition", "contextId"),
        RangeIndexSpec("Proposition", "status"),
        RangeIndexSpec("Proposition", "level"),
        RangeIndexSpec("Proposition", "effectiveConfidence"),
        RangeIndexSpec("Mention", "resolvedId"),
        RangeIndexSpec("ProcessedChunk", "sourceId"),
        RangeIndexSpec("Source", "kind"),
    )

    @Bean
    @ConditionalOnBean(Ai::class)
    @ConditionalOnProperty(prefix = "embabel.dice.store", name = ["type"], havingValue = "graph")
    @ConditionalOnProperty(
        prefix = "embabel.dice.store.vector-index",
        name = ["enabled"],
        havingValue = "true",
        matchIfMissing = true,
    )
    fun propositionVectorIndexSchema(
        ai: Ai,
        embeddingServices: ObjectProvider<EmbeddingService>,
    ): SchemaCatalog {
        // A vector index is created AT the embedding model's dimension, so with no model
        // there is no dimension to create it at. Register nothing rather than guess: an
        // index at the wrong dimension is worse than none, because writes to it succeed.
        // The catalog is rebuilt on the next boot, by which time a model configured at
        // first run is registered.
        val embeddingService = runCatching { embeddingService(ai, embeddingServices) }
            .getOrElse {
                logger.warn("Skipping proposition vector index schema: no embedding model ({})", it.message)
                return SchemaCatalog.of()
            }
        val dimensions = runCatching { embeddingService.dimensions }
            .getOrElse {
                logger.warn("Skipping proposition vector index schema: no embedding model ({})", it.message)
                return SchemaCatalog.of()
            }
        val spec = propositionVectorIndexSpec(dimensions)
        logger.info("Registering proposition vector index schema: {} (model={})", spec, embeddingService.name)
        return SchemaCatalog.of(spec).withVersion(embeddingService.name)
    }

    /**
     * The schema (DDL) for the proposition vector index. Its label, property, name, and similarity
     * come straight from [DrivinePropositionRepository]'s canonical constants — the same identity the
     * `@VectorIndex` annotation gives `loadNearest` and the `findClusters` Cypher — so all three paths
     * target one index. Only [dimensions] varies, since it comes from the embedding model at runtime.
     */
    /**
     * The same index, ensured on demand instead of at startup.
     *
     * The catalog above can only register what it can see WHEN IT IS BUILT, and a host that starts
     * without an embedding model has nothing to register. Its own note said the catalog would be
     * rebuilt on the next boot — true while such a host restarted after a model was configured,
     * and no longer true of one that applies a provider key without restarting. The index was then
     * never made: the model came alive, was used, and every read failed with "There is no such
     * vector schema index".
     *
     * A catalog cannot answer it either. `SchemaCatalog` holds materialised specs and
     * `SchemaManager` snapshots the catalog beans when it is built, so a later `enforce()` applies
     * the same empty catalog. This goes through `PersistenceManager.indexes`, which is the public
     * way to converge one index without a catalog at all.
     *
     * The host decides when — see [PropositionVectorIndexConvergence].
     */
    @Bean
    @ConditionalOnBean(Ai::class)
    @ConditionalOnProperty(prefix = "embabel.dice.store", name = ["type"], havingValue = "graph")
    @ConditionalOnProperty(
        prefix = "embabel.dice.store.vector-index",
        name = ["enabled"],
        havingValue = "true",
        matchIfMissing = true,
    )
    fun propositionVectorIndexConvergence(
        persistenceManager: PersistenceManager,
        ai: Ai,
        embeddingServices: ObjectProvider<EmbeddingService>,
    ): PropositionVectorIndexConvergence = PropositionVectorIndexConvergence(
        persistenceManager = persistenceManager,
        // Resolved per call, not captured: the model that matters here is one that arrived after
        // this bean was built.
        dimensionsOf = PropositionVectorIndexConvergence.dimensionsFrom { embeddingService(ai, embeddingServices) },
        spec = ::propositionVectorIndexSpec,
    )

    /**
     * Delegated to the repository, which owns the identity constants and now needs the spec at
     * runtime as well — [DrivinePropositionRepository.reembedAll] reconciles the index itself. Two
     * builders would be two chances for the startup DDL and the re-embed to describe different
     * indexes.
     */
    internal fun propositionVectorIndexSpec(dimensions: Int): VectorIndexSpec =
        DrivinePropositionRepository.vectorIndexSpec(dimensions)

    // ---- In-memory backend (default) ----

    @Bean
    @ConditionalOnBean(Ai::class)
    @ConditionalOnMissingBean(PropositionRepository::class)
    fun inMemoryPropositionRepository(
        ai: Ai,
        embeddingServices: ObjectProvider<EmbeddingService>,
    ): PropositionRepository {
        logger.info("Wiring in-memory proposition store")
        return InMemoryPropositionRepository(storeEmbeddingService("in-memory", ai, embeddingServices))
    }

    @Bean
    @ConditionalOnMissingBean(ChunkHistoryStore::class)
    fun inMemoryChunkHistoryStore(): ChunkHistoryStore = InMemoryChunkHistoryStore()

    @Bean
    @ConditionalOnMissingBean(ProjectionRecordStore::class)
    fun inMemoryProjectionRecordStore(): ProjectionRecordStore = InMemoryProjectionRecordStore()

    @Bean
    @ConditionalOnMissingBean(CollectorRecordStore::class)
    fun inMemoryCollectorRecordStore(): CollectorRecordStore = InMemoryCollectorRecordStore()

    @Bean
    @ConditionalOnBean(PropositionRepository::class)
    @ConditionalOnMissingBean(DecayManager::class)
    fun inMemoryDecayManager(repository: PropositionRepository): DecayManager =
        InMemoryDecayManager(repository)
}

/**
 * Schedules the decay tick (materialise cached confidence, then apply lifecycle transitions). Split
 * out so `@EnableScheduling` is only switched on when decay is enabled. Resolves the [DecayManager]
 * lazily via [ObjectProvider] so it's robust to the backend that registered it.
 */
@AutoConfiguration(after = [DiceStorageAutoConfiguration::class])
@ConditionalOnProperty(
    prefix = "embabel.dice.store.decay",
    name = ["enabled"],
    havingValue = "true",
    matchIfMissing = true,
)
@EnableConfigurationProperties(DiceStoreProperties::class)
@EnableScheduling
class DiceDecaySchedulingConfiguration(
    private val decayManager: ObjectProvider<DecayManager>,
    private val properties: DiceStoreProperties,
) {
    private val logger = LoggerFactory.getLogger(DiceDecaySchedulingConfiguration::class.java)

    @Scheduled(fixedDelayString = "\${embabel.dice.store.decay.interval-ms:3600000}")
    fun tick() {
        val manager = decayManager.ifAvailable ?: return
        val result = manager.tick(
            DecaySweepConfig(
                policy = DecayStatusPolicy(kMultiplier = properties.decay.k),
                pruneStale = properties.decay.pruneStale,
            )
        )
        logger.debug("Decay tick result: {}", result)
    }
}
