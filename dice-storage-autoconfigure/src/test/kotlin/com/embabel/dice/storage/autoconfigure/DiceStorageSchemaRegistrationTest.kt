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

import com.embabel.dice.storage.CollectorTraceSchema
import com.embabel.dice.storage.DiceStorageSchema
import com.embabel.dice.storage.LineageSchema
import org.assertj.core.api.Assertions.assertThat
import org.drivine.manager.GraphObjectManager
import org.drivine.manager.PersistenceManager
import org.springframework.transaction.PlatformTransactionManager
import org.drivine.schema.SchemaCatalog
import org.drivine.schema.SchemaItemSpec
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock
import org.springframework.beans.factory.getBeanProvider
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider
import org.springframework.context.annotation.Configuration
import org.springframework.core.type.filter.AssignableTypeFilter

/**
 * The autoconfigure half of the registration guard `dice-storage` states in full.
 *
 * A whole-graph drift check hides dice's own storage by reading the [DiceStorageSchema] beans the
 * application registered, so a store whose schema reaches the database through a hand-written
 * catalog gets its constraints — which is what mints its labels in Neo4j — while staying outside the
 * exclusion. Its nodes are then reported as domain drift on every check. That is the shape of the
 * defect this whole change closes, and this test is what makes it fail loudly here.
 *
 * The rule checked is one-directional on purpose. This module wires the proposition store, the
 * lineage stores and the collector trace store; the metamodel governance stores have no wiring here
 * yet, so requiring every dice schema on the classpath to be registered would demand DDL for stores
 * this module never creates. What it does require is that nothing this module *does* ensure against
 * a database belongs to a dice store it left out of the exclusion.
 */
class DiceStorageSchemaRegistrationTest {

    private val graphRunner = ApplicationContextRunner()
        .withConfiguration(
            AutoConfigurations.of(
                DiceStorageAutoConfiguration::class.java,
                CollectorAutoConfiguration::class.java,
            ),
        )
        .withUserConfiguration(StubDrivineConfig::class.java)
        .withPropertyValues("embabel.dice.store.type=graph")

    @Test
    fun `no dice store's schema reaches the database without contributing to ownership`() {
        graphRunner.run { ctx ->
            val registered = ctx.getBeanProvider<DiceStorageSchema>().orderedStream().toList()
                .map { it::class }
                .toSet()
            val ensured: Set<SchemaItemSpec> = ctx.getBeanProvider<SchemaCatalog>().orderedStream().toList()
                .flatMap { catalog -> catalog.items }
                .toSet()

            diceStorageSchemasOnClasspath()
                .filter { schema -> schema::class !in registered }
                .forEach { unregistered ->
                    assertThat(unregistered.specs().filter { spec -> spec in ensured })
                        .describedAs(
                            "%s is ensured against the database without being registered as a " +
                                "DiceStorageSchema, so every label it declares drifts",
                            unregistered::class.simpleName,
                        )
                        .isEmpty()
                }
        }
    }

    @Test
    fun `the graph backend registers the schemas of the stores it wires`() {
        // Named explicitly, because the rule above passes trivially for a module that registers
        // nothing. These are the two dice stores this module's graph backend creates beans for.
        graphRunner.run { ctx ->
            val registered = ctx.getBeanProvider<DiceStorageSchema>().orderedStream().toList()

            assertThat(registered).contains(LineageSchema, CollectorTraceSchema)
        }
    }

    @Test
    fun `the in-memory backend registers no graph schema at all`() {
        ApplicationContextRunner()
            .withConfiguration(
                AutoConfigurations.of(
                    DiceStorageAutoConfiguration::class.java,
                    CollectorAutoConfiguration::class.java,
                ),
            )
            .run { ctx ->
                assertThat(ctx.getBeanProvider<DiceStorageSchema>().orderedStream().toList()).isEmpty()
                assertThat(ctx.getBeanProvider<SchemaCatalog>().orderedStream().toList()).isEmpty()
            }
    }

    /**
     * The Drivine handles the graph stores are wired with. Nothing here talks to a database: the
     * question is which schema beans the autoconfiguration registers, and the stores only have to be
     * constructible for their conditions to resolve.
     */
    @Configuration
    open class StubDrivineConfig {

        @Bean
        open fun persistenceManager(): PersistenceManager = mock()

        @Bean
        open fun graphObjectManager(): GraphObjectManager = mock()

        /** Store beans that manage their own transactions ask for one of these at wiring time. */
        @Bean
        open fun transactionManager(): PlatformTransactionManager = mock()
    }

    /** Every `DiceStorageSchema` singleton on this module's classpath. */
    private fun diceStorageSchemasOnClasspath(): List<DiceStorageSchema> {
        val scanner = ClassPathScanningCandidateComponentProvider(false)
        scanner.addIncludeFilter(AssignableTypeFilter(DiceStorageSchema::class.java))
        return scanner.findCandidateComponents("com.embabel.dice.storage")
            .mapNotNull { definition -> definition.beanClassName }
            .mapNotNull { name -> Class.forName(name).kotlin.objectInstance as DiceStorageSchema? }
    }
}
