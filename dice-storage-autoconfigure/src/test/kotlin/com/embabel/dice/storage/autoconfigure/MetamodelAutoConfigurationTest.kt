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

import com.embabel.dice.metamodel.DeclaredObservedDiffer
import com.embabel.dice.metamodel.DeclaredSchema
import com.embabel.dice.metamodel.DeclaredSchemaSource
import com.embabel.dice.metamodel.DriftCheckRunner
import com.embabel.dice.metamodel.DriftQuarantinePolicy
import com.embabel.dice.metamodel.MetamodelStore
import com.embabel.dice.metamodel.MetamodelVersion
import com.embabel.dice.metamodel.ObservedSchemaSource
import com.embabel.dice.proposition.PropositionRepository
import com.embabel.dice.proposition.store.InMemoryPropositionRepository
import com.embabel.dice.storage.DrivineMetamodelStore
import org.assertj.core.api.Assertions.assertThat
import org.drivine.manager.PersistenceManager
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.context.annotation.AnnotationConfigApplicationContext
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.env.MapPropertySource

/**
 * Slice tests for [MetamodelAutoConfiguration]: no real Neo4j -- just a [NoOpPersistenceManager] and
 * an in-memory [PropositionRepository] as collaborator beans -- checking only that the
 * `embabel.dice.metamodel.enabled` gate and the [DeclaredSchemaSource] gate wire (or don't wire) the
 * feature as documented.
 */
class MetamodelAutoConfigurationTest {

    private var context: AnnotationConfigApplicationContext? = null

    @AfterEach
    fun tearDown() {
        context?.close()
    }

    /** The collaborator beans every scenario needs, regardless of whether the feature is enabled. */
    @Configuration
    open class CollaboratorBeans {
        @Bean
        open fun persistenceManager(): PersistenceManager = NoOpPersistenceManager()

        @Bean
        open fun propositionRepository(): PropositionRepository = InMemoryPropositionRepository()
    }

    /** A [DeclaredSchemaSource] bean, present only in scenarios that expect the runner to activate. */
    @Configuration
    open class DeclaredSchemaSourceBean {
        @Bean
        open fun declaredSchemaSource(): DeclaredSchemaSource = DeclaredSchemaSource {
            DeclaredSchema(
                version = MetamodelVersion(
                    schemaName = "test-schema",
                    contentHash = "hash",
                    entityTypeNames = listOf("Person"),
                    entityTypeLabels = mapOf("Person" to emptySet()),
                    entityTypeProperties = mapOf("Person" to emptySet()),
                    relationshipNames = emptyList(),
                ),
                relationshipTypeNames = emptySet(),
            )
        }
    }

    private fun startContext(vararg configs: Class<*>, enabled: Boolean): AnnotationConfigApplicationContext {
        val ctx = AnnotationConfigApplicationContext()
        ctx.environment.propertySources.addFirst(
            MapPropertySource("test-metamodel-props", mapOf("embabel.dice.metamodel.enabled" to enabled.toString())),
        )
        ctx.register(*configs)
        ctx.refresh()
        context = ctx
        return ctx
    }

    @Test
    fun `off by default -- no drift-check beans are registered`() {
        val ctx = startContext(
            CollaboratorBeans::class.java,
            DeclaredSchemaSourceBean::class.java,
            MetamodelAutoConfiguration::class.java,
            enabled = false,
        )

        assertThat(ctx.getBeanNamesForType(DriftCheckRunner::class.java)).isEmpty()
        assertThat(ctx.getBeanNamesForType(ObservedSchemaSource::class.java)).isEmpty()
        assertThat(ctx.getBeanNamesForType(MetamodelStore::class.java)).isEmpty()
    }

    @Test
    fun `enabled with a declared schema source -- the full drift-check runner wires up with its defaults`() {
        val ctx = startContext(
            CollaboratorBeans::class.java,
            DeclaredSchemaSourceBean::class.java,
            MetamodelAutoConfiguration::class.java,
            enabled = true,
        )

        assertThat(ctx.getBean(DriftCheckRunner::class.java)).isNotNull()
        assertThat(ctx.getBean(ObservedSchemaSource::class.java)).isInstanceOf(DrivineObservedSchemaSource::class.java)
        assertThat(ctx.getBean(MetamodelStore::class.java)).isInstanceOf(DrivineMetamodelStore::class.java)
        assertThat(ctx.getBean(DeclaredObservedDiffer::class.java)).isNotNull()
        assertThat(ctx.getBean(DriftQuarantinePolicy::class.java)).isNotNull()
    }

    @Test
    fun `enabled but no declared schema source -- everything else wires up except the runner`() {
        val ctx = startContext(
            CollaboratorBeans::class.java,
            MetamodelAutoConfiguration::class.java,
            enabled = true,
        )

        assertThat(ctx.getBeanNamesForType(DriftCheckRunner::class.java)).isEmpty()
        // The rest of the feature's plumbing still wires up -- it's only the runner that needs a
        // declared schema to make sense of.
        assertThat(ctx.getBean(ObservedSchemaSource::class.java)).isNotNull()
        assertThat(ctx.getBean(MetamodelStore::class.java)).isNotNull()
    }

    @Test
    fun `a consumer's own bean wins over the default`() {
        val ctx = startContext(
            CollaboratorBeans::class.java,
            DeclaredSchemaSourceBean::class.java,
            CustomRunnerBean::class.java,
            MetamodelAutoConfiguration::class.java,
            enabled = true,
        )

        assertThat(ctx.getBean(DriftCheckRunner::class.java)).isSameAs(CustomRunnerBean.INSTANCE)
    }

    /** A consumer-supplied [DriftCheckRunner] bean, declared statically (not captured locally) so
     * Spring's CGLIB configuration-class enhancer can instantiate it with a plain no-arg constructor. */
    @Configuration
    open class CustomRunnerBean {
        @Bean
        open fun driftCheckRunner(): DriftCheckRunner = INSTANCE

        companion object {
            val INSTANCE: DriftCheckRunner = object : DriftCheckRunner {
                override fun run(dryRun: Boolean) = throw UnsupportedOperationException("unused in this test")
            }
        }
    }
}
