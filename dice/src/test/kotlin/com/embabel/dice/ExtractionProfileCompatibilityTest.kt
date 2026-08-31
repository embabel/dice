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
package com.embabel.dice

import com.embabel.agent.core.ContextId
import com.embabel.agent.core.DataDictionary
import com.embabel.dice.common.EntityResolver
import com.embabel.dice.common.KnownEntity
import com.embabel.dice.common.Relations
import com.embabel.dice.common.SourceAnalysisContext
import com.embabel.dice.common.resolver.AlwaysCreateEntityResolver
import com.embabel.dice.proposition.extraction.ExtractionContentProfileRef
import com.embabel.dice.proposition.extraction.ExtractionPerspective
import com.embabel.dice.proposition.extraction.ExtractionRunRef
import com.embabel.dice.provenance.ContentAddressedLocator
import com.embabel.dice.provenance.SourceLocator
import com.embabel.dice.provenance.SourceRevisionRef
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The compatibility boundary this slice claims for `SourceAnalysisContext`, from Kotlin:
 * source-level constructor and `copy` calls written before profiles keep compiling and keep
 * seeing null, every Java-visible constructor descriptor that existed survives, and the old
 * `copy` descriptor does not — the same split Wave A declared for `sourceRevision`.
 */
class ExtractionProfileCompatibilityTest {

    private fun context(
        profile: ExtractionContentProfileRef? = null,
        currentRun: ExtractionRunRef? = null,
    ) = SourceAnalysisContext(
        schema = DataDictionary.fromClasses("profile-compatibility"),
        entityResolver = AlwaysCreateEntityResolver,
        contextId = ContextId("profile-compatibility"),
        profile = profile,
        currentRun = currentRun,
    )

    @Test
    fun `legacy Kotlin source constructors and copy calls see no profile and no run`() {
        val legacy = SourceAnalysisContext(
            schema = DataDictionary.fromClasses("profile-compatibility"),
            entityResolver = AlwaysCreateEntityResolver,
            contextId = ContextId("profile-compatibility"),
        ).copy(promptVariables = mapOf("legacy" to true))

        assertEquals(true, legacy.promptVariables["legacy"])
        assertNull(legacy.profile)
        assertNull(legacy.currentRun)

        // The Java-facing builder is unchanged too, and its result carries neither.
        val built = SourceAnalysisContext
            .withContextId("profile-compatibility")
            .withEntityResolver(AlwaysCreateEntityResolver)
            .withSchema(DataDictionary.fromClasses("profile-compatibility"))
        assertNull(built.profile)
        assertNull(built.currentRun)
    }

    @Test
    fun `new Kotlin source constructors and copy calls carry a profile and a run`() {
        val profile = ExtractionContentProfileRef("house-style", "v3")
        val run = ExtractionRunRef("run-42")

        val fromConstructor = context(profile = profile, currentRun = run)
            .copy(promptVariables = mapOf("profiled" to true))
        assertSame(profile, fromConstructor.profile)
        assertSame(run, fromConstructor.currentRun)
        assertEquals(true, fromConstructor.promptVariables["profiled"])

        val fromHelpers = context().withProfile(profile).withCurrentRun(run)
        assertSame(profile, fromHelpers.profile)
        assertSame(run, fromHelpers.currentRun)
    }

    @Test
    fun `a profile rides alongside a locator and a revision without disturbing either`() {
        val locator = ContentAddressedLocator("profile-compatibility-source")
        val revision = SourceRevisionRef(locator.key(), "r4")

        val context = SourceAnalysisContext(
            schema = DataDictionary.fromClasses("profile-compatibility"),
            entityResolver = AlwaysCreateEntityResolver,
            contextId = ContextId("profile-compatibility"),
            sourceLocator = locator,
            perspective = ExtractionPerspective.USER,
            sourceRevision = revision,
            profile = ExtractionContentProfileRef("house-style", "v3"),
            currentRun = ExtractionRunRef("run-42"),
        )

        assertSame(locator, context.sourceLocator)
        assertSame(revision, context.sourceRevision)
        assertSame(ExtractionPerspective.USER, context.perspective)
    }

    @Test
    fun `every constructor descriptor that existed before profiles survives`() {
        val marker = Class.forName("kotlin.jvm.internal.DefaultConstructorMarker")
        // Kotlin publishes the @JvmOverloads constructors with a trailing DefaultConstructorMarker
        // because contextId is a value class; the marker is part of the descriptor a compiled
        // caller links against.
        val declared = listOf(
            DataDictionary::class.java,
            EntityResolver::class.java,
            String::class.java,
            List::class.java,
            Relations::class.java,
            Map::class.java,
            SourceLocator::class.java,
            ExtractionPerspective::class.java,
            Boolean::class.javaPrimitiveType!!,
            Map::class.java,
            SourceRevisionRef::class.java,
            ExtractionContentProfileRef::class.java,
            ExtractionRunRef::class.java,
        )
        val published = SourceAnalysisContext::class.java.constructors
            .map { it.parameterTypes.toList() }
            .toSet()

        // 3..11 are the descriptors Wave A left behind.
        for (arity in 3..11) {
            assertTrue(
                declared.take(arity) + marker in published,
                "constructor of $arity arguments no longer published",
            )
        }
        // 12 and 13 are what this slice adds, on the end.
        for (arity in 12..13) {
            assertTrue(
                declared.take(arity) + marker in published,
                "constructor of $arity arguments was not published",
            )
        }
    }

    @Test
    fun `the old copy descriptor is outside the approved boundary`() {
        // Adding a field to a data class rewrites copy and componentN. That half of the ABI is
        // not claimed, here or in Wave A; only source and Java-constructor compatibility are.
        val copyArities = SourceAnalysisContext::class.java.declaredMethods
            .filter { it.name.startsWith("copy") && !it.name.endsWith("\$default") }
            .map { it.parameterCount }
        assertEquals(listOf(13), copyArities)

        val componentCount = SourceAnalysisContext::class.java.declaredMethods
            .count { it.name.startsWith("component") }
        assertEquals(13, componentCount)
    }

    @Test
    fun `known entity and relation helpers are unaffected by a profile`() {
        val profile = ExtractionContentProfileRef("house-style", "v3")
        val base = context(profile = profile)

        val widened = base
            .withRelations(Relations.empty())
            .withKnownEntities(*emptyArray<KnownEntity>())
            .withMintNewEntities(true)
            .withMintedEntityProperties(mapOf("owner" to "tenant"))

        assertSame(profile, widened.profile)
        assertEquals(true, widened.mintNewEntities)
    }
}
