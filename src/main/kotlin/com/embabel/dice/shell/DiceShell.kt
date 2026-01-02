package com.embabel.dice.shell

import com.embabel.agent.api.common.AiBuilder
import com.embabel.agent.core.DataDictionary
import com.embabel.agent.rag.ingestion.ContentChunker
import com.embabel.agent.rag.ingestion.TikaHierarchicalContentReader
import com.embabel.agent.rag.model.Chunk
import com.embabel.dice.common.EntityResolver
import com.embabel.dice.common.SourceAnalysisContext
import com.embabel.dice.common.resolver.AlwaysCreateEntityResolver
import com.embabel.dice.common.resolver.InMemoryEntityResolver
import com.embabel.dice.pipeline.PropositionBuilders
import com.embabel.dice.projection.graph.GraphProjector
import com.embabel.dice.projection.graph.LenientProjectionPolicy
import com.embabel.dice.projection.graph.LlmGraphProjector
import com.embabel.dice.projection.memory.*
import com.embabel.dice.projection.prolog.DefaultPrologProjector
import com.embabel.dice.projection.prolog.PrologEngine
import com.embabel.dice.projection.prolog.PrologSchema
import com.embabel.dice.proposition.Proposition
import com.embabel.dice.proposition.PropositionExtractor
import com.embabel.dice.proposition.PropositionRepository
import com.embabel.dice.proposition.extraction.LlmPropositionExtractor
import com.embabel.dice.proposition.store.InMemoryPropositionRepository
import com.embabel.dice.query.oracle.ToolOracle
import com.embabel.dice.text2graph.SourceAnalyzer
import com.embabel.dice.text2graph.builder.InMemoryObjectGraphGraphProjector
import com.embabel.dice.text2graph.builder.KnowledgeGraphBuilders
import com.embabel.dice.text2graph.support.LlmSourceAnalyzer
import com.embabel.dice.text2graph.support.ParallelSourceAnalyzer
import com.embabel.dice.text2graph.support.SourceAnalyzerProperties
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.slf4j.LoggerFactory
import org.springframework.shell.standard.ShellComponent
import org.springframework.shell.standard.ShellMethod
import java.io.FileInputStream


@ShellComponent("Extraction commands for Dice")
internal class DiceShell(
    private val aiBuilder: AiBuilder,
    private val sourceAnalyzerProperties: SourceAnalyzerProperties,
    private val entityResolver: EntityResolver = InMemoryEntityResolver(),
) {

    private val logger = LoggerFactory.getLogger(javaClass)

    val schema: DataDictionary = run {
        val schema = DataDictionary.fromClasses(Person::class.java, Animal::class.java)
        //schemaResolver.getSchema(EntitySearch(setOf("default")))!!
        logger.info("Using schema: {}", schema)
        schema
    }

    val holmesSchema = DataDictionary.fromClasses(
        Person::class.java,
        Animal::class.java,
        Landlord::class.java,
        Place::class.java,
        Detective::class.java,
        Doctor::class.java,
        Criminal::class.java,
    )

    private val ai = aiBuilder
        .withShowPrompts(true)
        .withShowLlmResponses(true)
        .ai()

    private val embeddingService = ai.withDefaultEmbeddingService()

    private val propositionRepository: PropositionRepository = InMemoryPropositionRepository(embeddingService)

    /**
     * Create source analyzer from config.
     * If multiple LLMs configured, uses ParallelSourceAnalyzer with agreement voting.
     */
    val sourceAnalyzer: SourceAnalyzer = run {
        val analyzers = sourceAnalyzerProperties.llms.map { llmOptions ->
            logger.info("Creating source analyzer for LLM: {}", llmOptions.model)
            LlmSourceAnalyzer(ai, llmOptions)
        }

        if (analyzers.size > 1) {
            logger.info(
                "Using parallel source analyzer with {} LLMs, minAgreement={}",
                analyzers.size,
                sourceAnalyzerProperties.minAgreement
            )
            ParallelSourceAnalyzer(
                analyzers = analyzers,
                config = ParallelSourceAnalyzer.Config(
                    minAgreement = sourceAnalyzerProperties.minAgreement
                )
            )
        } else {
            analyzers.first()
        }
    }

    /**
     * Create proposition extractor using the first configured LLM.
     */
    val propositionExtractor: PropositionExtractor = run {
        val llmOptions = sourceAnalyzerProperties.llms.firstOrNull()
        if (llmOptions != null) {
            logger.info("Creating proposition extractor with LLM: {}", llmOptions.model)
            LlmPropositionExtractor(ai, llmOptions)
        } else {
            logger.info("Creating proposition extractor with default LLM")
            LlmPropositionExtractor(ai)
        }
    }

    @ShellMethod("Analyze The Adventure of the Dying Detective")
    fun holmes() {
        val hcr = TikaHierarchicalContentReader()
        val chunker = ContentChunker(
            ContentChunker.DefaultConfig(
                maxChunkSize = 10000,
                overlapSize = 200,
            )
        )
        val doc = hcr.parseContent(
            FileInputStream("data/dying_detective.txt"),
            "dying_detective"
        )
        val chunks = chunker.chunk(doc)
        println("Created ${chunks.size} chunks")

        val knowledgeGraphBuilder = KnowledgeGraphBuilders
            .withSourceAnalyzer(sourceAnalyzer)
            .withEntityResolver(InMemoryEntityResolver())
            .knowledgeGraphBuilder()
        val sourceAnalysisContext = SourceAnalysisContext(
            schema = holmesSchema,
            entityResolver = entityResolver,
        )

        val delta = knowledgeGraphBuilder.computeDelta(chunks, sourceAnalysisContext)

        for (entity in delta!!.newOrModifiedEntities()) {
            println(jacksonObjectMapper().writerWithDefaultPrettyPrinter().writeValueAsString(entity))
        }

        val projector = InMemoryObjectGraphGraphProjector()
        val objects = projector.project(holmesSchema, delta)
        for (o in objects) {
            println(o)
        }

        println("--- Summary of new or modified entities ---")
        for (entity in delta.newOrModifiedEntities().sortedBy { it.name }) {
            println("${entity.id} - ${entity.name}: (${entity.labels().joinToString(": ")}}) - ${entity.description}")
        }
    }

    @ShellMethod("Analyze sample data")
    fun mem() {

        val knowledgeGraphBuilder = KnowledgeGraphBuilders
            .withSourceAnalyzer(sourceAnalyzer)
            .withEntityResolver(AlwaysCreateEntityResolver)
            .knowledgeGraphBuilder()

        val chunks = listOf(
            Chunk(
                id = "1",
                text = """
                Alice is a 30-year-old person who owns a dog named Rex, a German Shepherd.
                Bob is a 25-year-old person who has a cat named Whiskers, a Siamese breed.
                Charlie, aged 35, is another person with no pets.
            """.trimIndent(),
                metadata = emptyMap(),
                // TODO fix this
                parentId = "",
            )
        )
        val sourceAnalysisContext = SourceAnalysisContext(
            schema = schema,
            entityResolver = entityResolver,
        )

        val projector = InMemoryObjectGraphGraphProjector()
        val delta = knowledgeGraphBuilder.computeDelta(chunks, sourceAnalysisContext)
        println(delta)

        // Debug: print schema info
        println("\n=== Schema Debug ===")
        println("Domain types: ${schema.domainTypes.map { "${it.name} -> ${it.labels}" }}")
        delta!!.newOrModifiedEntities().forEach { entity ->
            println("Entity '${entity.name}' labels: ${entity.labels()}")
            println("  - Lookup with full labels: ${schema.domainTypeForLabels(entity.labels())?.name}")
            val simpleLabels = entity.labels().map { it.substringAfterLast('.') }.toSet()
            println("  - Simple labels: $simpleLabels")
            println("  - Lookup with simple labels: ${schema.domainTypeForLabels(simpleLabels)?.name}")
        }
        println("===================\n")

        val entities = projector.project(schema, delta)
        println(entities)
    }

    @ShellMethod("Project sample data")
    fun project() {
        val sourceAnalyzer = LlmSourceAnalyzer(
            aiBuilder
                .withShowPrompts(true)
                .withShowLlmResponses(true)
                .ai(),
        )
        val projector = KnowledgeGraphBuilders
            .withSourceAnalyzer(sourceAnalyzer)
            .withEntityResolver(AlwaysCreateEntityResolver)
            .projector()

        val chunks = listOf(
            Chunk(
                id = "1",
                text = """
                Alice is a 30-year-old person who owns a dog named Rex, a German Shepherd.
                Bob is a 25-year-old person who has a cat named Whiskers, a Siamese breed.
                Charlie, aged 35, is another person with no pets.
                Alice also owns a cat named Harmony.
            """.trimIndent(),
                metadata = emptyMap(),
                // TODO fix this
                parentId = "",
            )
        )
        val sourceAnalysisContext = SourceAnalysisContext(
            schema = schema,
            entityResolver = entityResolver,
        )

//        val entities = projector.project(chunks, sourceAnalysisContext)
//        println(entities)

        val alice = projector.root(
            chunks, sourceAnalysisContext,
            Person::class.java
        ) {
            it.name == "Alice"
        }
        println(alice)
    }

    @ShellMethod("Extract propositions from sample data")
    fun propositions() {
        val pipeline = PropositionBuilders
            .withExtractor(propositionExtractor)
            .withEntityResolver(InMemoryEntityResolver())
            .withStore(propositionRepository)
            .build()

        val chunks = listOf(
            Chunk(
                id = "1",
                text = """
                Alice is a 30-year-old person who owns a dog named Rex, a German Shepherd.
                Bob is a 25-year-old person who has a cat named Whiskers, a Siamese breed.
                Charlie, aged 35, is another person with no pets.
                Alice and Bob are friends. Charlie lives in London.
            """.trimIndent(),
                metadata = emptyMap(),
                parentId = "",
            )
        )
        val sourceAnalysisContext = SourceAnalysisContext(
            schema = schema,
            entityResolver = entityResolver,
        )

        val result = pipeline.process(chunks, sourceAnalysisContext)

        println("\n=== Proposition Extraction Results ===")
        println("Total propositions: ${result.totalPropositions}")
        println("Fully resolved: ${result.fullyResolvedCount}")
        println("Partially resolved: ${result.partiallyResolvedCount}")
        println("Unresolved: ${result.unresolvedCount}")

        println("\n--- Propositions ---")
        for (prop in result.allPropositions) {
            println("\n${prop.text}")
            println("  Confidence: ${prop.confidence}, Decay: ${prop.decay}")
            println("  Mentions: ${prop.mentions.map { "${it.span}:${it.type}${if (it.resolvedId != null) "→${it.resolvedId}" else "?"}" }}")
            if (prop.reasoning != null) {
                println("  Reasoning: ${prop.reasoning}")
            }
        }

        println("\n--- Stored Propositions (via store) ---")
        val stored = pipeline.store().findAll()
        println("Store contains ${stored.size} propositions")
    }

    @ShellMethod("Extract propositions from The Adventure of the Dying Detective")
    fun holmesPropositions() {
        val hcr = TikaHierarchicalContentReader()
        val chunker = ContentChunker(
            ContentChunker.DefaultConfig(
                maxChunkSize = 5000,
                overlapSize = 200,
            )
        )
        val doc = hcr.parseContent(
            FileInputStream("data/dying_detective.txt"),
            "dying_detective"
        )
        val chunks = chunker.chunk(doc)
        println("Created ${chunks.size} chunks")

        val pipeline = PropositionBuilders
            .withExtractor(propositionExtractor)
            .withEntityResolver(InMemoryEntityResolver())
            .withStore(propositionRepository)
            .build()

        val sourceAnalysisContext = SourceAnalysisContext(
            schema = holmesSchema,
            entityResolver = entityResolver,
        )

        val result = pipeline.process(chunks, sourceAnalysisContext)

        println("\n=== Proposition Extraction Results ===")
        println("Total propositions: ${result.totalPropositions}")
        println("Fully resolved: ${result.fullyResolvedCount}")
        println("Partially resolved: ${result.partiallyResolvedCount}")
        println("Unresolved: ${result.unresolvedCount}")

        println("\n--- Summary by Entity ---")
        val byEntity = result.allPropositions
            .flatMap { prop -> prop.mentions.mapNotNull { m -> m.resolvedId?.let { it to prop } } }
            .groupBy({ it.first }, { it.second })
            .toList()
            .sortedByDescending { it.second.size }
            .take(10)

        for ((entityId, props) in byEntity) {
            val entityName = props.firstOrNull()?.mentions?.find { it.resolvedId == entityId }?.span ?: entityId
            println("\n$entityName (${props.size} propositions):")
            props.take(5).forEach { println("  - ${it.text}") }
            if (props.size > 5) println("  ... and ${props.size - 5} more")
        }
    }

    @ShellMethod("Extract propositions and project to graph relationships")
    fun graph() {
        val graphProjector = LlmGraphProjector(
            ai = ai,
            policy = LenientProjectionPolicy(confidenceThreshold = 0.5),
        )

        val pipeline = PropositionBuilders
            .withExtractor(propositionExtractor)
            .withEntityResolver(InMemoryEntityResolver())
            .withStore(propositionRepository)
            .withProjector(graphProjector)
            .build()

        val chunks = listOf(
            Chunk(
                id = "1",
                text = """
                Alice is a 30-year-old software engineer who owns a dog named Rex, a German Shepherd.
                Bob is Alice's colleague at TechCorp. He has a cat named Whiskers.
                Alice mentors Bob on cloud architecture.
                Charlie is the CEO of TechCorp. He lives in San Francisco.
                Alice reports to Charlie. Bob admires Charlie's leadership.
            """.trimIndent(),
                metadata = emptyMap(),
                parentId = "",
            )
        )

        val sourceAnalysisContext = SourceAnalysisContext(
            schema = schema,
            entityResolver = entityResolver,
        )

        println("\n=== Processing and Promoting ===")

        // First extract propositions
        val extractionResult = pipeline.process(chunks, sourceAnalysisContext)

        println("\n--- Extraction Results ---")
        println("Total propositions: ${extractionResult.totalPropositions}")
        println("Fully resolved: ${extractionResult.fullyResolvedCount}")

        println("\n--- Propositions ---")
        for (prop in extractionResult.allPropositions) {
            val resolvedIndicator = if (prop.isFullyResolved()) "✓" else "○"
            println("$resolvedIndicator ${prop.text}")
            println("  Confidence: ${prop.confidence}")
            val mentionStrs = prop.mentions.map { m ->
                val resolved = if (m.resolvedId != null) "→${m.resolvedId!!.take(8)}" else "?"
                "${m.span}:${m.type}[${m.role}]$resolved"
            }
            println("  Mentions: $mentionStrs")
        }

        // Then project to graph
        println("\n--- Projecting to Relationships ---")
        val graphResult = pipeline.project(GraphProjector::class, extractionResult, sourceAnalysisContext.schema)

        println("\n--- Projection Results ---")
        println("Projected: ${graphResult.successCount}")
        println("Skipped: ${graphResult.skipCount}")
        println("Failed: ${graphResult.failureCount}")

        println("\n--- Created Relationships ---")
        for (rel in graphResult.projected) {
            println("(${rel.sourceId.take(8)})-[:${rel.type}]->(${rel.targetId.take(8)})")
            println("  Confidence: ${rel.confidence}")
            println("  Source: ${rel.description?.take(60)}...")
        }

        if (graphResult.skipped.isNotEmpty()) {
            println("\n--- Skipped Propositions ---")
            for (skip in graphResult.skipped.take(5)) {
                println("- ${skip.proposition.text.take(50)}... (${skip.reason})")
            }
        }

        if (graphResult.failures.isNotEmpty()) {
            println("\n--- Failed Projections ---")
            for (failed in graphResult.failures.take(5)) {
                println("- ${failed.proposition.text.take(50)}... (${failed.reason})")
            }
        }
    }

//    /**
//     * Analyzes all chunks in the database and updates the knowledge graph.
//     */
//    @ShellMethod("Analyze files and build knowledge graph")
//    fun analyze(
//    ): String {
//        val tt = TransactionTemplate(platformTransactionManager)
//        val chunks = tt.execute { contentElementRepository.findAll().filterIsInstance<Chunk>() }!!
//        logger.debug("Analyzing chunks: {}", chunks)
//        knowledgeGraphBuilder.updateKnowledgeGraph(
//            chunks,
//            SourceAnalysisContext(
//                directions = """
//                Analyze project documentation and other sources to help users
//                understand the Embabel framework for building agentic applications on the JVM.
//                The audience is is software developers.
//                Capture all relevant concepts and other entities.
//                The goal is to identify important entities that will
//                help users search the documentation.
//                """.trimIndent(),
//                schema = schema
//            ),
//        )
//        return "Ingestion complete"
//    }

//    @Transactional(readOnly = true)
//    @ShellMethod("rag query")
//    fun rag(
//        @ShellOption query: String,
//        @ShellOption(defaultValue = "10") topK: Int,
//        @ShellOption(defaultValue = "0.3") similarityThreshold: Double
//    ): String {
//        val ragResponse = context.ai()
//            .rag()
//            .search(
//                RagRequest.query(query)
//                    .withTopK(topK)
//                    .withSimilarityThreshold(similarityThreshold)
//                    .withContentElementSearch(ContentElementSearch.CHUNKS_ONLY)
//                    .withEntitySearch(
//                        TypedEntitySearch(
//                            entities = listOf(
//                                Concept::class.java,
//                                Repo::class.java,
//                                Example::class.java,
//                            ),
//                            generateQueries = false,
//                        ),
//                    )
//                    .withHyDE(HyDE(wordCount = 40))
//                    .withDesiredMaxLatency(Duration.ofMinutes(5))
//            )
//        for (result in ragResponse.results.filter { it.match is EntityData }) {
//            val chunks = contentElementRepository.findChunksForEntity(result.match.id)
//            println(chunks)
//        }
//        val ragResponseFormatter = SimpleRagResponseFormatter
//        return "RAG response: \n" + ragResponseFormatter.format(ragResponse)
//    }

    @ShellMethod("Demonstrate memory projection from propositions")
    fun memory() {
        val pipeline = PropositionBuilders
            .withExtractor(propositionExtractor)
            .withEntityResolver(InMemoryEntityResolver())
            .withStore(propositionRepository)
            .build()

        // Sample text with various types of information about a user
        val chunks = listOf(
            Chunk(
                id = "1",
                text = """
                Alice is a senior software engineer who specializes in distributed systems.
                She has been working at TechCorp for 5 years.
                Alice prefers using Kotlin over Java for new projects.
                When discussing architecture, she always recommends starting with a monolith.
                Yesterday, Alice met with the team to discuss the new microservices migration.
                Alice tends to schedule meetings in the morning.
                She is an expert in Kubernetes and Docker.
                Last week, Alice gave a presentation on event-driven architecture.
            """.trimIndent(),
                metadata = emptyMap(),
                parentId = "",
            )
        )

        val sourceAnalysisContext = SourceAnalysisContext(
            schema = schema,
            entityResolver = entityResolver,
        )

        println("\n=== Extracting Propositions ===")
        val extractionResult = pipeline.process(chunks, sourceAnalysisContext)
        println("Extracted ${extractionResult.totalPropositions} propositions")

        // Find Alice's entity ID (assuming she was resolved)
        val aliceId = extractionResult.allPropositions
            .flatMap { it.mentions }
            .find { it.span.equals("Alice", ignoreCase = true) }
            ?.resolvedId ?: "alice-unknown"

        println("\n=== Memory Type Classification ===")
        val byType = extractionResult.allPropositions.groupBy { KeywordMatchingMemoryTypeClassifier.apply { it } }
        for ((type, props) in byType) {
            println("\n$type (${props.size} propositions):")
            props.forEach { println("  - ${it.text}") }
        }

        // Create memory projection
        val memoryProjection = DefaultMemoryProjection(propositionRepository)

        println("\n=== User Profile (Semantic Memory) ===")
        val profile = memoryProjection.projectUserProfile(aliceId)
        println("Facts (${profile.facts.size}):")
        profile.facts.forEach { println("  - $it") }
        println("Aggregate confidence: ${String.format("%.2f", profile.confidence)}")

        println("\n=== Recent Events (Episodic Memory) ===")
        val events = memoryProjection.projectRecentEvents(aliceId)
        if (events.isEmpty()) {
            println("  (no episodic memories found)")
        } else {
            events.forEach { println("  - ${it.asContext()}") }
        }

        println("\n=== Behavioral Rules (Procedural Memory) ===")
        val rules = memoryProjection.projectBehavioralRules(aliceId)
        if (rules.isEmpty()) {
            println("  (no procedural rules found)")
        } else {
            rules.forEach { println("  - ${it.asContext()}") }
        }

        println("\n=== Working Memory ===")
        val scope = MemoryScope.global(aliceId)
        val workingMemory = memoryProjection.projectWorkingMemory(
            scope = scope,
            sessionPropositions = extractionResult.allPropositions.take(3),
            budget = 20,
        )
        println("Total items: ${workingMemory.totalItems}")
        println("\n--- Context for LLM ---")
        println(workingMemory.contribution())

        // Demonstrate memory retrieval
        println("\n=== Memory Retrieval ===")
        val retriever = DefaultMemoryRetriever(propositionRepository)

        println("\nRecall about 'Kubernetes':")
        val kubernetesMemories = retriever.recall("Kubernetes", scope, topK = 5)
        kubernetesMemories.forEach { println("  - ${it.text}") }

        println("\nRecall semantic memories:")
        val semanticMemories = retriever.recallByType(MemoryType.SEMANTIC, scope, topK = 5)
        semanticMemories.forEach { println("  - ${it.text}") }
    }

    @ShellMethod("Demonstrate memory consolidation")
    fun consolidate() {
        // Add some "existing" propositions (simulating long-term memory)
        val existingProps = listOf(
            Proposition(
                text = "Alice is a software engineer",
                mentions = emptyList(),
                confidence = 0.8,
                decay = 0.1,
            ),
            Proposition(
                text = "Alice works at TechCorp",
                mentions = emptyList(),
                confidence = 0.75,
                decay = 0.1,
            ),
        )
        propositionRepository.saveAll(existingProps)

        // Simulate new session propositions
        val sessionProps = listOf(
            Proposition(
                text = "Alice is a senior software engineer", // reinforces existing
                mentions = emptyList(),
                confidence = 0.9,
                decay = 0.1,
            ),
            Proposition(
                text = "Alice prefers Kotlin", // new high-confidence
                mentions = emptyList(),
                confidence = 0.85,
                decay = 0.2,
            ),
            Proposition(
                text = "Alice mentioned something about testing", // low confidence
                mentions = emptyList(),
                confidence = 0.4,
                decay = 0.5,
            ),
        )

        println("\n=== Memory Consolidation Demo ===")
        println("\nExisting long-term memories:")
        existingProps.forEach { println("  - ${it.text} (conf: ${it.confidence})") }

        println("\nNew session propositions:")
        sessionProps.forEach { println("  - ${it.text} (conf: ${it.confidence})") }

        val consolidator = DefaultMemoryConsolidator(
            promotionThreshold = 0.6,
            similarityThreshold = 0.5,
        )

        val result = consolidator.consolidate(sessionProps, existingProps)

        println("\n--- Consolidation Results ---")
        println("\nPromoted to long-term (${result.promoted.size}):")
        result.promoted.forEach { println("  + ${it.text}") }

        println("\nReinforced existing (${result.reinforced.size}):")
        result.reinforced.forEach {
            println("  ~ ${it.text} (new conf: ${String.format("%.2f", it.confidence)})")
        }

        println("\nMerged (${result.merged.size}):")
        result.merged.forEach { merge ->
            println("  * ${merge.result.text}")
            println("    from: ${merge.sources.map { it.text.take(30) }}")
        }

        println("\nDiscarded (${result.discarded.size}):")
        result.discarded.forEach { println("  x ${it.text}") }

        println("\nTotal stored: ${result.storedCount}")
    }

    @ShellMethod("Project propositions to Prolog facts and rules")
    fun prolog() {
        // Schema with relationship types for Prolog demo
        val prologDemoSchema = DataDictionary.fromClasses(
            PrologPerson::class.java,
            Technology::class.java,
            Company::class.java,
            Place::class.java,
        )

        val graphProjector = LlmGraphProjector(
            ai = ai,
            policy = LenientProjectionPolicy(confidenceThreshold = 0.5),
        )

        val prologSchema = PrologSchema.withDefaults()
        val prologProjector = DefaultPrologProjector(prologSchema = prologSchema)

        val pipeline = PropositionBuilders
            .withExtractor(propositionExtractor)
            .withEntityResolver(InMemoryEntityResolver())
            .withStore(propositionRepository)
            .withProjector(graphProjector)
            .withProjector(prologProjector)
            .build()

        val chunks = listOf(
            Chunk(
                id = "1",
                text = """
                Alice is a software engineer who is an expert in Kubernetes and Docker.
                Bob is Alice's colleague at TechCorp. They are friends.
                Alice mentors Bob on cloud architecture. Bob knows Python and Java.
                Charlie is the team lead at TechCorp. Alice reports to Charlie.
                Charlie manages the infrastructure team.
                David lives in San Francisco and works at TechCorp.
                Alice and David are friends from college.
            """.trimIndent(),
                metadata = emptyMap(),
                parentId = "",
            )
        )

        val sourceAnalysisContext = SourceAnalysisContext(
            schema = prologDemoSchema,
            entityResolver = entityResolver,
        )

        println("\n=== Prolog Projection Demo ===\n")

        // Step 1: Extract propositions
        println("--- Step 1: Extracting Propositions ---")
        val extractionResult = pipeline.process(chunks, sourceAnalysisContext)
        println("Extracted ${extractionResult.totalPropositions} propositions")
        println("Fully resolved: ${extractionResult.fullyResolvedCount}")

        // Show propositions with details
        println("\nPropositions (showing roles and resolution):")
        extractionResult.allPropositions.forEach { prop ->
            val indicator = if (prop.isFullyResolved()) "✓" else "○"
            println("  $indicator ${prop.text}")
            println("    confidence: ${prop.confidence}")
            prop.mentions.forEach { m ->
                val resolved = if (m.resolvedId != null) "→${m.resolvedId!!.take(8)}" else "UNRESOLVED"
                println("      [${m.role}] ${m.span} : ${m.type} $resolved")
            }
        }

        // Step 2: Project to graph relationships
        println("\n--- Step 2: Projecting to Graph Relationships ---")
        val graphResult = pipeline.project(GraphProjector::class, extractionResult, prologDemoSchema)
        println("Projected: ${graphResult.successCount} relationships")
        println("Skipped: ${graphResult.skipCount}")
        println("Failed: ${graphResult.failureCount}")

        // Show relationships
        println("\nRelationships:")
        graphResult.projected.forEach { rel ->
            println("  ${rel.sourceId.take(12)} -[:${rel.type}]-> ${rel.targetId.take(12)}")
        }

        // Step 3: Project to Prolog
        println("\n--- Step 3: Projecting to Prolog ---")
        val prologResult = prologProjector.projectAll(graphResult.projected)
        println("Generated ${prologResult.factCount} Prolog facts")

        println("\n--- Prolog Facts ---")
        prologResult.facts.forEach { fact ->
            println("  ${fact.toProlog()}")
        }

        println("\n--- Confidence Facts ---")
        prologResult.confidenceFacts.take(5).forEach { conf ->
            println("  ${conf.toProlog()}")
        }
        if (prologResult.confidenceFacts.size > 5) {
            println("  ... and ${prologResult.confidenceFacts.size - 5} more")
        }

        println("\n--- Grounding Facts (provenance) ---")
        prologResult.groundingFacts.take(5).forEach { ground ->
            println("  ${ground.toProlog()}")
        }
        if (prologResult.groundingFacts.size > 5) {
            println("  ... and ${prologResult.groundingFacts.size - 5} more")
        }

        // Generate complete theory
        println("\n--- Complete Prolog Theory ---")
        val theory = prologResult.toTheory(prologSchema)
        println(theory)

        // Create Prolog engine and run queries
        println("\n=== Running Prolog Queries ===")

        if (prologResult.facts.isEmpty()) {
            println("  (no facts to query - skipping queries to avoid infinite recursion)")
        } else {
            val engine = PrologEngine.fromProjection(prologResult, prologSchema)

            // Run example queries
            println("\n--- Query: Who is an expert in something? ---")
            println("  ?- expert_in(X, Y).")
            val experts = engine.queryAll("expert_in(X, Y)")
            if (experts.any { it.success }) {
                experts.filter { it.success }.forEach { result ->
                    println("    ${result.bindings["X"]} is expert in ${result.bindings["Y"]}")
                }
            } else {
                println("    (no results)")
            }

            println("\n--- Query: Who are friends? ---")
            println("  ?- friend_of(X, Y).")
            val friends = engine.queryAll("friend_of(X, Y)")
            if (friends.any { it.success }) {
                friends.filter { it.success }.forEach { result ->
                    println("    ${result.bindings["X"]} is friends with ${result.bindings["Y"]}")
                }
            } else {
                println("    (no results)")
            }

            println("\n--- Query: Who are colleagues? ---")
            println("  ?- colleague_of(X, Y).")
            val colleagues = engine.queryAll("colleague_of(X, Y)")
            if (colleagues.any { it.success }) {
                colleagues.filter { it.success }.take(5).forEach { result ->
                    println("    ${result.bindings["X"]} is colleague of ${result.bindings["Y"]}")
                }
                if (colleagues.size > 5) println("    ... and more")
            } else {
                println("    (no results)")
            }

            println("\n--- Query: Who works at what company? ---")
            println("  ?- works_at(X, Y).")
            val worksAt = engine.queryAll("works_at(X, Y)")
            if (worksAt.any { it.success }) {
                worksAt.filter { it.success }.forEach { result ->
                    println("    ${result.bindings["X"]} works at ${result.bindings["Y"]}")
                }
            } else {
                println("    (no results)")
            }

            println("\n--- Query: Who reports to whom? ---")
            println("  ?- reports_to(X, Y).")
            val reportsTo = engine.queryAll("reports_to(X, Y)")
            if (reportsTo.any { it.success }) {
                reportsTo.filter { it.success }.forEach { result ->
                    println("    ${result.bindings["X"]} reports to ${result.bindings["Y"]}")
                }
            } else {
                println("    (no results)")
            }
        }

        println("\n=== Prolog Predicates Available ===")
        prologSchema.allPredicates().forEach { pred ->
            println("  - $pred/2")
        }
    }

    @ShellMethod("Ask natural language questions answered via Prolog reasoning")
    fun oracle() {
        // Schema with relationship types
        val prologDemoSchema = DataDictionary.fromClasses(
            PrologPerson::class.java,
            Technology::class.java,
            Company::class.java,
            Place::class.java,
        )

        val graphProjector = LlmGraphProjector(
            ai = ai,
            policy = LenientProjectionPolicy(confidenceThreshold = 0.5),
        )

        val prologSchema = PrologSchema.withDefaults()
        val prologProjector = DefaultPrologProjector(prologSchema = prologSchema)

        val pipeline = PropositionBuilders
            .withExtractor(propositionExtractor)
            .withEntityResolver(InMemoryEntityResolver())
            .withStore(propositionRepository)
            .withProjector(graphProjector)
            .withProjector(prologProjector)
            .build()

        val chunks = listOf(
            Chunk(
                id = "1",
                text = """
                Alice is a software engineer who is an expert in Kubernetes and Docker.
                Bob is Alice's colleague at TechCorp. They are friends.
                Alice mentors Bob on cloud architecture. Bob knows Python and Java.
                Charlie is the team lead at TechCorp. Alice reports to Charlie.
                Charlie manages the infrastructure team.
                David lives in San Francisco and works at TechCorp.
                Alice and David are friends from college.
            """.trimIndent(),
                metadata = emptyMap(),
                parentId = "",
            )
        )

        val sourceAnalysisContext = SourceAnalysisContext(
            schema = prologDemoSchema,
            entityResolver = entityResolver,
        )

        println("\n=== Oracle Demo: Natural Language Q&A ===\n")

        // Step 1: Build the knowledge base
        println("--- Building Knowledge Base ---")
        val extractionResult = pipeline.process(chunks, sourceAnalysisContext)
        println("Extracted ${extractionResult.totalPropositions} propositions")

        val graphResult = pipeline.project(GraphProjector::class, extractionResult, prologDemoSchema)
        println("Projected ${graphResult.successCount} relationships")

        val prologResult = prologProjector.projectAll(graphResult.projected)
        println("Generated ${prologResult.factCount} Prolog facts")

        // Build entity name mapping for readable answers
        val entityNames = mutableMapOf<String, String>()
        extractionResult.allPropositions.forEach { prop ->
            prop.mentions.forEach { mention ->
                if (mention.resolvedId != null) {
                    // Map both UUID and underscore-normalized versions
                    entityNames[mention.resolvedId!!] = mention.span
                    entityNames[mention.resolvedId!!.replace("-", "_")] = mention.span
                }
            }
        }
        println("Entity names: ${entityNames.values.distinct()}")

        // Step 2: Create the Oracle (uses LLM tool calling)
        val oracle = ToolOracle(
            ai = ai,
            prologResult = prologResult,
            prologSchema = prologSchema,
            propositionRepository = propositionRepository,
            entityNames = entityNames,
        )

        // Step 3: Ask questions
        println("\n--- Asking Questions ---\n")

        val questions = listOf(
            "Who is an expert in Kubernetes?",
            "What technologies does Bob know?",
            "Who does Alice report to?",
            "Where does David live?",
            "Who works at TechCorp?",
            "Are Alice and Bob friends?",
            "What is Charlie's role?",
        )

        for (questionText in questions) {
            println("Q: $questionText")
            val answer = oracle.ask(questionText)
            println("A: ${answer.text}")
            println("   [${answer.source}, confidence: ${String.format("%.1f", answer.confidence)}]")
            if (answer.grounding.isNotEmpty()) {
                println("   Grounded by: ${answer.grounding.size} proposition(s)")
            }
            if (answer.reasoning != null) {
                println("   Reasoning: ${answer.reasoning}")
            }
            println()
        }
    }
}