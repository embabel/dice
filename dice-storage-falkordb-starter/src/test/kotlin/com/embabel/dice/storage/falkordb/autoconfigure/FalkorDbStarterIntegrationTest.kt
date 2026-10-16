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
package com.embabel.dice.storage.falkordb.autoconfigure

import com.embabel.agent.core.ContextId
import com.embabel.common.ai.model.EmbeddingService
import com.embabel.common.ai.model.PricingModel
import com.embabel.common.core.types.TextSimilaritySearchRequest
import com.embabel.dice.incremental.BookmarkKey
import com.embabel.dice.incremental.ChunkHistoryStore
import com.embabel.dice.incremental.HashKey
import com.embabel.dice.incremental.ProcessedChunkRecord
import com.embabel.dice.projection.lineage.CollectorRecordStore
import com.embabel.dice.projection.lineage.InMemoryCollectorRecordStore
import com.embabel.dice.projection.lineage.InMemoryProjectionRecordStore
import com.embabel.dice.projection.lineage.ProjectionRecordStore
import com.embabel.dice.proposition.DecayManager
import com.embabel.dice.proposition.EntityMention
import com.embabel.dice.proposition.Proposition
import com.embabel.dice.proposition.PropositionQuery
import com.embabel.dice.proposition.PropositionRepository
import com.embabel.dice.proposition.extraction.ExtractionRunStore
import com.embabel.dice.proposition.extraction.PropositionRunLinkStore
import com.embabel.dice.proposition.store.InMemoryDecayManager
import com.embabel.dice.storage.autoconfigure.CollectorAutoConfiguration
import com.embabel.dice.storage.autoconfigure.DiceStorageAutoConfiguration
import com.embabel.dice.storage.falkordb.FalkorDbChunkHistoryStore
import com.embabel.dice.storage.falkordb.FalkorDbPropositionRepository
import com.github.dockerjava.api.model.ExposedPort
import com.github.dockerjava.api.model.PortBinding
import com.github.dockerjava.api.model.Ports
import org.assertj.core.api.Assertions.assertThat
import org.drivine.connection.ConnectionProperties
import org.drivine.connection.DataSourceMap
import org.drivine.connection.DatabaseRegistry
import org.drivine.connection.DatabaseType
import org.drivine.connection.FalkorDbConnectionProvider
import org.drivine.connection.FalkorDbTransactionMode
import org.drivine.mapper.SubtypeRegistry
import org.drivine.query.QuerySpecification
import org.drivine.query.grammar.CypherDialect
import org.drivine.schema.ConstraintSpec
import org.drivine.schema.SchemaCatalog
import org.drivine.schema.SchemaGrammar
import org.drivine.schema.SchemaItemInfo
import org.drivine.schema.SchemaStatement
import org.drivine.transaction.TransactionContextHolder
import org.junit.jupiter.api.Test
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.spy
import org.mockito.kotlin.whenever
import org.springframework.beans.factory.config.DestructionAwareBeanPostProcessor
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.wait.strategy.Wait
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.utility.DockerImageName
import java.net.URLClassLoader
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.UUID
import javax.tools.ToolProvider
import kotlin.io.path.readText
import kotlin.io.path.writeText

@Testcontainers
class FalkorDbStarterIntegrationTest {
    private fun graph() = "starter_${UUID.randomUUID().toString().replace("-", "")}"

    private fun runner(graph: String) = ApplicationContextRunner()
        .withInitializer { context ->
            context.beanFactory.addBeanPostProcessor(object : DestructionAwareBeanPostProcessor {
                override fun requiresDestruction(bean: Any) = bean is DatabaseRegistry
                override fun postProcessBeforeDestruction(bean: Any, beanName: String) {
                    if (bean is DatabaseRegistry) bean.databaseNames.forEach { bean.connectionProvider(it)?.end() }
                }
            })
        }
        .withConfiguration(AutoConfigurations.of(
            FalkorDbStorageAutoConfiguration::class.java, DiceStorageAutoConfiguration::class.java,
            CollectorAutoConfiguration::class.java,
        ))
        .withPropertyValues(*configuration(graph))
        .withPropertyValues("embabel.dice.store.decay.enabled=false", "embabel.dice.extraction.runs.enabled=true")

    private fun configuration(graph: String): Array<String> {
        val block = Path.of("../docs/falkordb.md").readText()
            .substringAfter("```properties\n").substringBefore("\n```")
            .replace("\${FALKORDB_HOST}", server.host)
            .replace("\${FALKORDB_PORT}", server.getMappedPort(6379).toString())
            .replace("\${FALKORDB_GRAPH}", graph)
        val properties = java.util.Properties().also { it.load(java.io.StringReader(block)) }
        return properties.stringPropertyNames().map { "$it=${properties.getProperty(it)}" }.toTypedArray()
    }

    @Test
    fun `property bound cold and repeated contexts persist propositions and chunk processing`() {
        val graph = graph()
        val fact = Proposition(contextId = ContextId("example"), text = "A durable fact", mentions = emptyList(), confidence = 0.9,
            metadata = mapOf("nested" to listOf("one", "two")))
        val record = ProcessedChunkRecord(BookmarkKey(ContextId("example"), "source"), HashKey(ContextId("example"), "hash"),
            0, 4, Instant.parse("2026-04-05T06:07:08.123456789Z"))
        runner(graph).run { context ->
            assertThat(context).hasNotFailed()
            val repository = context.getBean(PropositionRepository::class.java)
            assertThat(repository).isInstanceOf(FalkorDbPropositionRepository::class.java)
            assertThat(repository.supportsVector).isFalse()
            repository.save(fact)
            val chunks = context.getBean(ChunkHistoryStore::class.java)
            assertThat(chunks).isInstanceOf(FalkorDbChunkHistoryStore::class.java)
            chunks.recordProcessed(record)
            assertThat(context.getBean(DecayManager::class.java)).isInstanceOf(InMemoryDecayManager::class.java)
            assertThat(context.getBean(ProjectionRecordStore::class.java)).isInstanceOf(InMemoryProjectionRecordStore::class.java)
            assertThat(context.getBean(CollectorRecordStore::class.java)).isInstanceOf(InMemoryCollectorRecordStore::class.java)
            assertThat(context.getBean(com.embabel.dice.spi.CollectorTraceStore::class.java))
                .isInstanceOf(com.embabel.dice.spi.InMemoryCollectorTraceStore::class.java)
            assertThat(context).doesNotHaveBean(ExtractionRunStore::class.java).doesNotHaveBean(PropositionRunLinkStore::class.java)
                .doesNotHaveBean(SchemaCatalog::class.java).doesNotHaveBean("propositionVectorIndexConvergence")
            val registry = context.getBean(DatabaseRegistry::class.java)
            val provider = registry.connectionProvider("knowledge")!!
            val constraints = provider.schemaGrammar.parseConstraintRows(query(provider as FalkorDbConnectionProvider,
                provider.schemaGrammar.listConstraintsQuery()))
            assertThat(constraints).hasSize(3).allMatch { it.status == "OPERATIONAL" }
        }
        repeat(2) {
            runner(graph).run { context ->
                assertThat(context).hasNotFailed()
                assertThat(context.getBean(PropositionRepository::class.java).findById(fact.id)).isEqualTo(fact)
                val chunks = context.getBean(ChunkHistoryStore::class.java)
                assertThat(chunks.isProcessed(record.hashKey)).isTrue()
                assertThat(chunks.getLastBookmark(record.bookmarkKey)?.processedAt).isEqualTo(record.processedAt)
            }
        }
    }

    @Test
    fun `default alias resolves actual datasource and individual application overrides win`() {
        val repository = com.embabel.dice.proposition.store.InMemoryPropositionRepository()
        runner(graph()).withPropertyValues("embabel.dice.store.falkordb.datasource=default")
            .withBean(PropositionRepository::class.java, { repository }).run { context ->
                assertThat(context).hasNotFailed()
                assertThat(context.getBean(PropositionRepository::class.java)).isSameAs(repository)
                assertThat(context.getBean(ChunkHistoryStore::class.java)).isInstanceOf(FalkorDbChunkHistoryStore::class.java)
            }
        val chunks = com.embabel.dice.incremental.InMemoryChunkHistoryStore()
        runner(graph()).withBean(ChunkHistoryStore::class.java, { chunks }).run { context ->
            assertThat(context).hasNotFailed()
            assertThat(context.getBean(ChunkHistoryStore::class.java)).isSameAs(chunks)
            assertThat(context.getBean(PropositionRepository::class.java)).isInstanceOf(FalkorDbPropositionRepository::class.java)
        }
    }

    private class SyntheticEmbeddingService : EmbeddingService {
        override val name = "synthetic"
        override val provider = "local-test"
        override val dimensions = 2
        override val pricingModel: PricingModel? = null
        override fun embed(text: String) = floatArrayOf(1f, 0f)
        override fun embed(texts: List<String>) = texts.map(::embed)
    }

    @Test
    fun `application embedding bean enables native exact vector search without Ai`() {
        runner(graph()).withBean(EmbeddingService::class.java, { SyntheticEmbeddingService() }).run { context ->
            assertThat(context).hasNotFailed()
            val repository = context.getBean(PropositionRepository::class.java)
            assertThat(repository.supportsVector).isTrue()
            val saved = repository.save(Proposition(contextId = ContextId("example"), text = "Embedded fact", mentions = emptyList(), confidence = 0.9))
            assertThat(repository.findSimilarWithScores(TextSimilaritySearchRequest("fact", 0.0, 5), PropositionQuery.againstContext("example")))
                .hasSize(1)
            assertThat(repository.findById(saved.id)).isEqualTo(saved)
        }
    }

    @Test
    fun `duplicate preexisting identity makes context fail before publishing stores`() {
        val graph = graph()
        provider(graph).useProvider { provider ->
            query(provider, "CREATE (:DiceProposition {id:'duplicate'}) CREATE (:DiceProposition {id:'duplicate'}) RETURN {value:1} AS result")
            repeat(2) {
                runner(graph).run { context ->
                    assertThat(context).hasFailed()
                    assertThat(context.startupFailure).hasStackTraceContaining("FalkorDB")
                }
            }
            assertThat((query(provider, "MATCH (p:DiceProposition) RETURN {value:count(p)} AS result").single()["value"] as Number).toInt()).isEqualTo(2)
        }
    }

    @Test
    fun `pending and failed readiness statuses propagate through starter without publishing repository`() {
        for (status in listOf("PENDING", "FAILED")) {
            provider(graph()).useProvider { real ->
                FalkorDbPropositionRepository(real)
                val grammar = real.schemaGrammar
                val delayed = object : SchemaGrammar by grammar {
                    override fun createConstraint(spec: ConstraintSpec): List<SchemaStatement> = emptyList()
                    override fun parseConstraintRows(rows: List<Any?>): List<SchemaItemInfo> =
                        grammar.parseConstraintRows(rows).map { it.copy(status = status) }
                }
                val controlled = spy(real)
                doReturn(delayed).whenever(controlled).schemaGrammar
                val sources = DataSourceMap(mapOf(real.name to ConnectionProperties(type = DatabaseType.FALKORDB)))
                val registry = DatabaseRegistry(DataSourceMap()).also { it.register(controlled) }
                ApplicationContextRunner().withConfiguration(AutoConfigurations.of(FalkorDbStorageAutoConfiguration::class.java))
                    .withPropertyValues("embabel.dice.store.type=falkordb", "embabel.dice.store.falkordb.datasource=${real.name}")
                    .withBean(DatabaseRegistry::class.java, { registry }).withBean(DataSourceMap::class.java, { sources })
                    .withBean(TransactionContextHolder::class.java, { TransactionContextHolder(registry) })
                    .run { context ->
                        assertThat(context).hasFailed()
                        assertThat(context.startupFailure).hasStackTraceContaining(
                            if (status == "FAILED") "identity constraint failed" else "identity constraint did not become operational")
                    }
            }
        }
    }

    @Test
    fun `documented Kotlin and Java examples compile and run against starter repository`() {
        val documentation = Path.of("../docs/falkordb.md").readText()
        runner(graph()).run { context ->
            val repository = context.getBean(PropositionRepository::class.java)
            val source = com.embabel.dice.proposition.store.InMemoryPropositionRepository()
            val original = storeFact(source).copy(
                metadata = mapOf("count" to 3L, "large" to Long.MAX_VALUE, "ratio" to 1.5f),
                mentions = listOf(EntityMention("Alice", "Person",
                    hints = mapOf("counts" to listOf(3L), "ratio" to 1.5f))),
                provenanceEntries = listOf(
                    com.embabel.dice.provenance.ProvenanceEntry(com.embabel.dice.provenance.UriLocator("https://example.test/evidence")),
                ),
            )
            source.save(original)
            val leanSource = object : PropositionRepository by source {
                override fun findAll() = source.findAll().map { it.copy(provenanceEntries = emptyList()) }
            }
            copyKnowledge(leanSource, repository)
            val restored = repository.findById(original.id)!!
            assertThat(restored).isEqualTo(original.copy(
                metadata = mapOf("count" to 3, "large" to Long.MAX_VALUE, "ratio" to 1.5),
                mentions = original.mentions.map { it.copy(hints = mapOf("counts" to listOf(3), "ratio" to 1.5)) },
            ))
            val kotlinBody = Path.of("src/test/kotlin/com/embabel/dice/storage/falkordb/autoconfigure/FalkorDbStarterIntegrationTest.kt")
                .readText().substringAfter("// Kotlin documentation example\n").substringBefore("\n// End Kotlin documentation example")
            assertThat(documentation).contains(kotlinBody)
            val migrationBody = Path.of("src/test/kotlin/com/embabel/dice/storage/falkordb/autoconfigure/FalkorDbStarterIntegrationTest.kt")
                .readText().substringAfter("// Migration documentation example\n").substringBefore("\n// End migration documentation example")
            assertThat(documentation).contains(migrationBody)
            val java = documentation.substringAfter("```java\n").substringBefore("\n```")
            val directory = Files.createTempDirectory("dice-java-example")
            try {
                val source = directory.resolve("FalkorExample.java").also { it.writeText(java) }
                val compiler = ToolProvider.getSystemJavaCompiler()
                assertThat(compiler.run(null, null, null, "-classpath", System.getProperty("java.class.path"), "-d", directory.toString(), source.toString()))
                    .isZero()
                URLClassLoader(arrayOf(directory.toUri().toURL()), javaClass.classLoader).use { loader ->
                    val result = loader.loadClass("FalkorExample").getMethod("storeFact", PropositionRepository::class.java)
                        .invoke(null, repository) as Proposition
                    assertThat(repository.findById(result.id)).isEqualTo(result)
                }
            } finally {
                directory.toFile().deleteRecursively()
            }
        }
    }

    @Test
    fun `migration verification rejects changed field values`() {
        val source = com.embabel.dice.proposition.store.InMemoryPropositionRepository()
        storeFact(source)
        runner(graph()).run { context ->
            val repository = context.getBean(PropositionRepository::class.java)
            val changed = object : PropositionRepository by repository {
                override fun findById(id: String) = repository.findById(id)?.copy(text = "Unexpected replacement")
            }
            val failure = org.junit.jupiter.api.assertThrows<IllegalStateException> { copyKnowledge(source, changed) }
            assertThat(failure).hasMessage("Proposition fields changed")
        }
    }

    @Test
    fun `consuming BOM requires direct Jedis version and resolves tested API`() {
        val directory = Files.createTempDirectory("dice-consumer-bom")
        try {
            val documentation = Path.of("../docs/falkordb.md").readText()
            val pom = documentation.substringAfter("```xml\n").substringBefore("\n```")
            directory.resolve("pom.xml").writeText(pom)
            val output = directory.resolve("effective.xml")
            val process = ProcessBuilder(Path.of("../mvnw").toAbsolutePath().toString(), "-q", "-f",
                directory.resolve("pom.xml").toString(), "help:effective-pom", "-Doutput=$output")
                .redirectErrorStream(true).redirectOutput(directory.resolve("maven.log").toFile()).start()
            try {
                check(process.waitFor(90, java.util.concurrent.TimeUnit.SECONDS)) { "Consumer effective-POM check timed out" }
                assertThat(process.exitValue()).withFailMessage(directory.resolve("maven.log").readText()).isZero()
            } finally {
                if (process.isAlive) process.destroyForcibly()
            }
            val factory = javax.xml.parsers.DocumentBuilderFactory.newInstance()
            val doc = factory.newDocumentBuilder().parse(output.toFile())
            val xpath = javax.xml.xpath.XPathFactory.newInstance().newXPath()
            assertThat(xpath.evaluate("/project/dependencies/dependency[artifactId='jedis']/version", doc)).isEqualTo("8.0.1")
            assertThat(xpath.evaluate("/project/dependencyManagement/dependencies/dependency[artifactId='jedis']/version", doc))
                .isNotEqualTo("8.0.1").isNotBlank()
            val properties = java.util.Properties()
            redis.clients.jedis.Jedis::class.java.getResourceAsStream("/META-INF/maven/redis.clients/jedis/pom.properties")!!.use(properties::load)
            assertThat(properties.getProperty("version")).isEqualTo("8.0.1")
        } finally {
            directory.toFile().deleteRecursively()
        }
    }

    private fun provider(graph: String) = FalkorDbConnectionProvider("knowledge", server.host, server.getMappedPort(6379), null,
        graph, FalkorDbTransactionMode.WARN, SubtypeRegistry(), CypherDialect.FALKORDB)

    private fun <T> FalkorDbConnectionProvider.useProvider(action: (FalkorDbConnectionProvider) -> T): T =
        try { action(this) } finally { end() }

    @Suppress("UNCHECKED_CAST")
    private fun query(provider: FalkorDbConnectionProvider, statement: String): List<Map<String, Any?>> {
        val connection = provider.connect()
        return try {
            connection.query(QuerySpecification.withStatement(statement).transform(Map::class.java)) as List<Map<String, Any?>>
        } finally { connection.release(null) }
    }

    companion object {
        @Container
        @JvmField
        val server = StarterServer(DockerImageName.parse(
            "falkordb/falkordb@sha256:0d793d4b249a9cf0837faa9f30fea1b86fb50086fc8aa21e9447078a07f995bc",
        )).withExposedPorts(6379).withCreateContainerCmdModifier {
            it.withEntrypoint("redis-server")
            it.hostConfig!!.withMemory(1024L * 1024 * 1024).withNanoCPUs(2_000_000_000)
                .withPortBindings(PortBinding(Ports.Binding.bindIpAndPort("127.0.0.1", 0), ExposedPort(6379)))
        }.withCommand("--loadmodule", "/var/lib/falkordb/bin/falkordb.so", "THREAD_COUNT", "2",
            "--save", "", "--appendonly", "no", "--maxmemory", "512mb", "--maxmemory-policy", "noeviction")
            .waitingFor(Wait.forListeningPort())
    }
}

// Kotlin documentation example
fun storeFact(repository: PropositionRepository): Proposition {
    val fact = Proposition(
        contextId = ContextId("example"), text = "A durable fact",
        mentions = emptyList(), confidence = 0.9,
    )
    repository.save(fact)
    check(repository.query(PropositionQuery.againstContext("example")).any { it.id == fact.id })
    return fact
}
// End Kotlin documentation example

class StarterServer(image: DockerImageName) : GenericContainer<StarterServer>(image)

// Migration documentation example
fun copyKnowledge(source: PropositionRepository, target: PropositionRepository) {
    val mapper = com.fasterxml.jackson.module.kotlin.jacksonObjectMapper().findAndRegisterModules()
    fun json(proposition: Proposition) = mapper.readTree(mapper.writeValueAsString(proposition))
    check(target.count() == 0) { "Use an empty target" }
    val complete = source.findAll().map { proposition ->
        proposition.copy(provenanceEntries = source.provenanceOf(proposition.id))
    }
    complete.forEach { proposition ->
        check(target.save(proposition).id == proposition.id) { "Source ID changed" }
        val restored = checkNotNull(target.findById(proposition.id)) { "Proposition missing" }
        check(json(restored) == json(proposition)) { "Proposition fields changed" }
        check(target.provenanceOf(proposition.id).toSet() == proposition.provenanceEntries.toSet()) {
            "Evidence changed"
        }
    }
    check(target.count() == complete.size) { "Destination count differs" }
}
// End migration documentation example
