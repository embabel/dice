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
package com.embabel.dice.common

import com.embabel.agent.core.ContextId
import com.embabel.agent.core.DataDictionary
import com.embabel.dice.common.resolver.AlwaysCreateEntityResolver
import com.embabel.dice.proposition.extraction.ExtractionContentProfileRef
import com.embabel.dice.proposition.extraction.ExtractionPerspective
import com.embabel.dice.provenance.ContentAddressedLocator
import com.embabel.dice.provenance.SourceRevisionRef
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

/**
 * Profile, perspective, schema and tenant are four independent dimensions of an analysis.
 * Setting one never constrains, coerces or perturbs another — this file is that claim, run as
 * a matrix rather than argued in a comment.
 *
 * Why it needs stating: perspective is the dimension a profile is most easily confused with.
 * Perspective describes conversational input (whose statements to mine); a profile is the
 * host's content policy. They answer different questions and a caller may combine them freely.
 */
class ExtractionContextIndependenceTest {

    private val profiles = listOf(
        null,
        ExtractionContentProfileRef("house-style", "v1"),
        ExtractionContentProfileRef("house-style", "v2"),
        ExtractionContentProfileRef("legal-review", "v1"),
    )
    private val perspectives = listOf(
        null,
        ExtractionPerspective.ALL,
        ExtractionPerspective.USER,
        ExtractionPerspective.NON_USER_RELATIONSHIPS,
    )
    private val schemas = listOf(
        DataDictionary.fromClasses("schema-one"),
        DataDictionary.fromClasses("schema-two"),
    )
    private val tenants = listOf(ContextId("tenant-one"), ContextId("tenant-two"))

    /** One matrix cell, named by index so the test never depends on how a dimension compares. */
    private data class Cell(
        val profile: Int,
        val perspective: Int,
        val schema: Int,
        val tenant: Int,
    )

    private fun matrix(): Map<Cell, SourceAnalysisContext> =
        profiles.indices.flatMap { p ->
            perspectives.indices.flatMap { e ->
                schemas.indices.flatMap { s ->
                    tenants.indices.map { t ->
                        Cell(p, e, s, t) to SourceAnalysisContext(
                            schema = schemas[s],
                            entityResolver = AlwaysCreateEntityResolver,
                            contextId = tenants[t],
                            perspective = perspectives[e],
                            profile = profiles[p],
                        )
                    }
                }
            }
        }.toMap()

    @Test
    fun `every combination of profile perspective schema and tenant is constructible`() {
        val matrix = matrix()

        assertEquals(64, matrix.size, "4 profiles x 4 perspectives x 2 schemas x 2 tenants")
        matrix.forEach { (cell, context) ->
            assertSame(profiles[cell.profile], context.profile, "profile at $cell")
            assertSame(perspectives[cell.perspective], context.perspective, "perspective at $cell")
            assertSame(schemas[cell.schema], context.schema, "schema at $cell")
            assertEquals(tenants[cell.tenant], context.contextId, "tenant at $cell")
        }
    }

    @Test
    fun `each pair of dimensions realises its whole cross product`() {
        val matrix = matrix()
        val observed = matrix.values.map {
            listOf(it.profile, it.perspective, it.schema, it.contextId)
        }
        val domains = listOf(profiles, perspectives, schemas, tenants)

        // For every ordered pair of dimensions, every combination of their values occurs. A
        // dimension that quietly disabled, defaulted or rejected another would leave a hole here.
        for (first in domains.indices) {
            for (second in domains.indices) {
                if (first == second) continue
                val expected = domains[first].flatMap { a -> domains[second].map { b -> a to b } }
                    .toSet()
                val actual = observed.map { it[first] to it[second] }.toSet()
                assertEquals(expected, actual, "cross product of dimensions $first and $second")
            }
        }
    }

    @Test
    fun `varying one dimension leaves the other three untouched`() {
        val baseline = SourceAnalysisContext(
            schema = schemas[0],
            entityResolver = AlwaysCreateEntityResolver,
            contextId = tenants[0],
            perspective = perspectives[1],
            profile = profiles[1],
        )

        for (profile in profiles) {
            val varied = baseline.copy(profile = profile)
            assertSame(profile, varied.profile)
            assertSame(baseline.perspective, varied.perspective)
            assertSame(baseline.schema, varied.schema)
            assertEquals(baseline.contextId, varied.contextId)
        }
        for (perspective in perspectives) {
            val varied = baseline.copy(perspective = perspective)
            assertSame(perspective, varied.perspective)
            assertSame(baseline.profile, varied.profile)
            assertSame(baseline.schema, varied.schema)
            assertEquals(baseline.contextId, varied.contextId)
        }
        for (schema in schemas) {
            val varied = baseline.copy(schema = schema)
            assertSame(schema, varied.schema)
            assertSame(baseline.profile, varied.profile)
            assertSame(baseline.perspective, varied.perspective)
            assertEquals(baseline.contextId, varied.contextId)
        }
        for (tenant in tenants) {
            val varied = baseline.copy(contextId = tenant)
            assertEquals(tenant, varied.contextId)
            assertSame(baseline.profile, varied.profile)
            assertSame(baseline.perspective, varied.perspective)
            assertSame(baseline.schema, varied.schema)
        }
    }

    @Test
    fun `the copy helpers move one field and no other`() {
        val locator = ContentAddressedLocator("independence-source")
        val base = SourceAnalysisContext(
            schema = schemas[0],
            entityResolver = AlwaysCreateEntityResolver,
            contextId = tenants[0],
            knownEntities = emptyList(),
            relations = Relations.empty(),
            promptVariables = mapOf("k" to "v"),
            sourceLocator = locator,
            perspective = ExtractionPerspective.USER,
            mintNewEntities = true,
            mintedEntityProperties = mapOf("owner" to "tenant-one"),
            sourceRevision = SourceRevisionRef(locator.key(), "r1"),
            profile = profiles[1],
        )

        // Comparing against copy(...) is a statement about every component at once: the helper
        // differs from the receiver in exactly the one field it names.
        val profile = ExtractionContentProfileRef("legal-review", "v9")
        assertEquals(base.copy(profile = profile), base.withProfile(profile))

        assertEquals(
            base.copy(perspective = ExtractionPerspective.ALL),
            base.withPerspective(ExtractionPerspective.ALL),
        )
    }

    @Test
    fun `a profile needs no locator and a locator needs no profile`() {
        val profile = profiles[1]!!
        val locator = ContentAddressedLocator("independence-source")

        val profileOnly = SourceAnalysisContext(
            schema = schemas[0],
            entityResolver = AlwaysCreateEntityResolver,
            contextId = tenants[0],
            profile = profile,
        )
        assertSame(profile, profileOnly.profile)
        assertNull(profileOnly.sourceLocator)

        val both = SourceAnalysisContext(
            schema = schemas[0],
            entityResolver = AlwaysCreateEntityResolver,
            contextId = tenants[0],
            sourceLocator = locator,
            sourceRevision = SourceRevisionRef(locator.key(), "r1"),
            profile = profile,
        )
        assertSame(profile, both.profile)
        assertNotNull(both.sourceRevision)

        // The one coupling that does exist is unaffected by a profile: a revision still has to
        // name the source being read.
        assertThrows(IllegalArgumentException::class.java) {
            SourceAnalysisContext(
                schema = schemas[0],
                entityResolver = AlwaysCreateEntityResolver,
                contextId = tenants[0],
                sourceLocator = locator,
                sourceRevision = SourceRevisionRef("content:something-else", "r1"),
                profile = profile,
            )
        }
    }
}
