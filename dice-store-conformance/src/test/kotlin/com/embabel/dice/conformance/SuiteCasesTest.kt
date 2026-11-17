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
package com.embabel.dice.conformance

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.platform.commons.annotation.Testable
import org.junit.platform.commons.support.AnnotationSupport
import org.junit.platform.commons.support.ReflectionSupport
import org.junit.platform.engine.discovery.DiscoverySelectors
import org.junit.platform.engine.support.descriptor.MethodSource
import org.junit.platform.launcher.TestExecutionListener
import org.junit.platform.launcher.TestIdentifier
import org.junit.platform.launcher.core.LauncherDiscoveryRequestBuilder
import org.junit.platform.launcher.core.LauncherFactory
import org.junit.platform.launcher.listeners.SummaryGeneratingListener
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import javax.lang.model.SourceVersion

/**
 * Checks that every case of every conformance suite, including cases a suite inherits, can be
 * overridden and disabled from a Java subclass.
 */
class SuiteCasesTest {

    /**
     * Finds the suites in the kit package.
     *
     * @return every abstract class whose simple name starts with Abstract and ends with ContractTest
     */
    private fun suites(): List<Class<*>> = ReflectionSupport.findAllClassesInPackage(
        "com.embabel.dice.conformance",
        { Modifier.isAbstract(it.modifiers) },
        { name ->
            val simpleName = name.substringAfterLast('.')
            simpleName.startsWith("Abstract") && simpleName.endsWith("ContractTest")
        },
    ).sortedBy { it.name }

    /**
     * Lists the cases a suite runs, declared on it or on any class it extends, whichever
     * annotation JUnit runs them with.
     *
     * @param suite the suite class to read
     * @return the methods that carry a `@Testable` annotation, such as `@Test`
     */
    private fun cases(suite: Class<*>): List<Method> =
        generateSequence(suite) { it.superclass }
            .takeWhile { it != Any::class.java }
            .flatMap { it.declaredMethods.asSequence() }
            .filter { AnnotationSupport.isAnnotated(it, Testable::class.java) }
            .sortedWith(compareBy({ it.declaringClass.name }, { it.name }))
            .toList()

    /**
     * Describes what is wrong with one case, if anything.
     *
     * @param suite the suite that declares the case
     * @param method the case to check
     * @return one `Suite.method: reason` line per problem, or an empty list when the case is fine
     */
    private fun problems(suite: Class<*>, method: Method): List<String> {
        val reasons = buildList {
            if (!Modifier.isPublic(method.modifiers)) add("not public")
            if (Modifier.isFinal(method.modifiers)) add("final, so a Java subclass cannot override it")
            if (Modifier.isStatic(method.modifiers)) add("static")
            if (!SourceVersion.isIdentifier(method.name)) {
                add("name is not a Java identifier")
            } else if (SourceVersion.isKeyword(method.name)) {
                add("name is a Java keyword")
            }
        }
        return reasons.map { "${suite.simpleName}.${method.name} (declared on ${method.declaringClass.simpleName}): $it" }
    }

    @Test
    fun `the kit holds every published suite and each one has cases`() {
        val found = suites()
        val names = found.map { it.simpleName }
        val published = listOf(
            AbstractChunkHistoryStoreContractTest::class.java,
            AbstractCollectorRecordStoreContractTest::class.java,
            AbstractCollectorTraceStoreContractTest::class.java,
            AbstractDriftReportStoreContractTest::class.java,
            AbstractExtractionRunStoreContractTest::class.java,
            AbstractFilteredVectorSearchContractTest::class.java,
            AbstractMetamodelVersionStoreContractTest::class.java,
            AbstractProjectionRecordStoreContractTest::class.java,
            AbstractPropositionRunLinkStoreContractTest::class.java,
            AbstractPropositionStoreContractTest::class.java,
        )
        assertTrue(found.containsAll(published), "The scan missed ${published - found.toSet()}; suites found: $names")
        found.forEach { suite ->
            assertTrue(cases(suite).isNotEmpty(), "${suite.simpleName} has no cases; suites found: $names")
        }
    }

    @Test
    fun `every case is a public open method with a name Java can declare`() {
        val problems = suites().flatMap { suite -> cases(suite).flatMap { problems(suite, it) } }
        assertEquals(emptyList<String>(), problems)
    }

    @Test
    fun `a Java subclass that disables one case skips that case and runs the rest`() {
        val summary = SummaryGeneratingListener()
        val skipped = mutableListOf<String>()
        val skipListener = object : TestExecutionListener {
            override fun executionSkipped(testIdentifier: TestIdentifier, reason: String) {
                val source = testIdentifier.source.orElse(null)
                if (source is MethodSource) skipped += source.methodName
            }
        }
        val request = LauncherDiscoveryRequestBuilder.request()
            .selectors(DiscoverySelectors.selectClass(JAVA_EXAMPLE))
            .build()

        LauncherFactory.create().execute(request, summary, skipListener)

        val result = summary.summary
        assertEquals(11L, result.testsFoundCount, "tests found")
        assertEquals(10L, result.testsSucceededCount, "tests succeeded")
        assertEquals(0L, result.testsFailedCount, "tests failed")
        assertEquals(1L, result.testsSkippedCount, "tests skipped")
        assertEquals(listOf("markStaleByPropositionMarksEveryRecordOfTheProposition"), skipped)
    }

    private companion object {
        const val JAVA_EXAMPLE =
            "com.embabel.dice.conformance.example.ProjectionRecordStoreContractJavaInteropTest"
    }
}
