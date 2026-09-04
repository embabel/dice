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
package com.embabel.dice.storage

import org.drivine.annotation.NodeFragment
import org.drivine.schema.SchemaCatalog
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider
import org.springframework.core.type.filter.AnnotationTypeFilter
import org.springframework.core.type.filter.AssignableTypeFilter
import org.springframework.core.type.filter.TypeFilter
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource

/**
 * The guard that would have caught the union-branch defect.
 *
 * A whole-graph drift check hides dice's own storage, and it works out what that is from the
 * [DiceStorageSchema] beans the application registered. A dice store nobody registered therefore
 * reports its own nodes as domain drift, forever, on every check. The guard this replaces was
 * written against the very hand-kept list of schema objects that was missing one, so it could never
 * fail: it compared the list with itself.
 *
 * These assertions compare two independent things — what the classpath holds, and what the running
 * Spring context registered — so a schema object that exists and is wired nowhere fails the build in
 * the slice that adds it, with no list here to remember to update.
 *
 * [TestApplication] is this repository's only wiring of a [DrivineObservedSchemaSource]. A host wires
 * it the same way, and the autoconfigure module's own
 * `DiceStorageSchemaRegistrationTest` holds its graph backend to the second rule below.
 */
@SpringBootTest(classes = [TestApplication::class])
class DiceStorageSchemaRegistrationTest {

    companion object {
        @JvmStatic
        @DynamicPropertySource
        fun neo4jProperties(registry: DynamicPropertyRegistry) = Neo4jTestContainer.registerProperties(registry)
    }

    @Autowired
    private lateinit var registeredSchemas: List<DiceStorageSchema>

    @Autowired
    private lateinit var registeredCatalogs: List<SchemaCatalog>

    @Test
    fun `every dice storage schema on the classpath is registered`() {
        val onClasspath = diceStorageSchemasOnClasspath().map { it::class }.toSet()
        val registered = registeredSchemas.map { it::class }.toSet()

        assertEquals(
            onClasspath,
            registered,
            "a dice storage schema that reaches no registration is invisible to the drift " +
                "exclusion, so every label it declares is reported as domain drift once its store " +
                "writes anything. Register it beside the store it belongs to. Missing: " +
                "${onClasspath - registered}; registered and absent from the classpath scan: " +
                "${registered - onClasspath}",
        )
    }

    @Test
    fun `no dice store's schema reaches the database without contributing to ownership`() {
        // The other direction, and the one that catches a hand-written
        // `SchemaCatalog.of(SomeSchema.specs())`. Such a catalog creates the store's constraints,
        // which mints its labels in the database, while leaving the store out of the ownership the
        // observer subtracts. Deriving every catalog through `diceStorageCatalog` closes it.
        val registered = registeredSchemas.map { it::class }.toSet()
        val declaredSpecs = registeredCatalogs.flatMap { catalog -> catalog.items }.toSet()

        diceStorageSchemasOnClasspath()
            .filter { schema -> schema::class !in registered }
            .forEach { unregistered ->
                val leaked = unregistered.specs().filter { spec -> spec in declaredSpecs }
                assertTrue(
                    leaked.isEmpty(),
                    "${unregistered::class.simpleName} is ensured against the database without being " +
                        "registered as a DiceStorageSchema, so its labels drift: $leaked",
                )
            }
    }

    @Test
    fun `every dice node fragment is part of the core ownership set`() {
        // The fragments dice persists directly are ownership no registration can switch off, because
        // the observer reads propositions and mentions through their shapes. A new one has to join
        // `DiceOwnedSchema.CORE_NODE_FRAGMENTS` for its label to be recognised as dice's own.
        val onClasspath = scan("com.embabel.dice.storage.model", AnnotationTypeFilter(NodeFragment::class.java))
            .map { it.kotlin }
            .toSet()

        assertEquals(
            onClasspath,
            DiceOwnedSchema.CORE_NODE_FRAGMENTS.toSet(),
            "a @NodeFragment dice writes carries no ownership shape, so its label is reported as " +
                "domain drift on every whole-graph check",
        )
    }

    /** Every `DiceStorageSchema` singleton the storage classpath holds, main and test alike. */
    private fun diceStorageSchemasOnClasspath(): List<DiceStorageSchema> =
        scan("com.embabel.dice.storage", AssignableTypeFilter(DiceStorageSchema::class.java))
            .mapNotNull { candidate -> candidate.kotlin.objectInstance as DiceStorageSchema? }

    private fun scan(basePackage: String, filter: TypeFilter): List<Class<*>> {
        val scanner = ClassPathScanningCandidateComponentProvider(false)
        scanner.addIncludeFilter(filter)
        return scanner.findCandidateComponents(basePackage)
            .mapNotNull { definition -> definition.beanClassName }
            .map { name -> Class.forName(name) }
    }
}
