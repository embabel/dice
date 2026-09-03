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
package com.embabel.dice.proposition.extraction

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.io.File
import java.lang.reflect.Executable
import java.lang.reflect.Field
import java.lang.reflect.Modifier
import java.time.Instant

/**
 * The canaries: four kinds of text that must have no way into a stored failure record.
 *
 * Each of [LEAK_CANDIDATES] is a string, and `ExtractionFailure` has no parameter, property or
 * field anywhere in its reachable shape that will take one. So every canary here is a shape
 * assertion: the compiler already rejects the call, and these tests pin the shape the compiler is
 * reading, so an edit that adds a text field back turns one of them red.
 *
 * **The lines that no longer compile.** Written out because a shape assertion is easy to read as
 * abstract, and these are the concrete calls it forbids:
 *
 * ```kotlin
 * ExtractionFailure(ExtractionFailureCode.DECODE_FAILED, ExtractionRunFixtures.SOURCE_TEXT)
 * ExtractionFailure.of(ExtractionFailureCode.INTERNAL, "Extract every claim about: <document>")
 * ExtractionFailure(ExtractionFailureCode.INTERNAL, "marguerite.okonkwo@acme-holdings.example")
 * ExtractionFailure.of(ExtractionFailureCode.INTERNAL, "AKIAIOSFODNN7EXAMPLE")
 * ExtractionFailure.fromThrowable(ExtractionFailureCode.DECODE_FAILED, providerException)
 * ```
 *
 * The first four have no string-shaped parameter to land in and the fifth has no throwable-shaped
 * one, which is the whole of the closure: a caller holding something regrettable finds nowhere on
 * this type to put it.
 */
class ExtractionFailureVocabularyTest {

    @Test
    fun `nothing on a failure record accepts any of the canary strings`() {
        val entryPoints = entryPointsOf(ExtractionFailure::class.java, ExtractionFailureMeasure::class.java)

        LEAK_CANDIDATES.forEach { (what, value) ->
            val accepting = entryPoints
                .filter { entry -> entry.parameterTypes.any { it.isAssignableFrom(value.javaClass) } }
                .map { "${it.declaringClass.simpleName}.${it.name}" }

            assertThat(accepting).describedAs("entry points accepting %s", what).isEmpty()
        }
    }

    @Test
    fun `nothing on a failure record accepts a throwable`() {
        // A throwable is a bag of strings: the provider's message, the fragment that failed to
        // parse, the stack. An API that took one and promised to read almost none of it is the
        // contract that erodes on the next edit, so there is no such API.
        val accepting = entryPointsOf(ExtractionFailure::class.java, ExtractionFailureMeasure::class.java)
            .filter { entry -> entry.parameterTypes.any { Throwable::class.java.isAssignableFrom(it) } }
            .map { "${it.declaringClass.simpleName}.${it.name}" }

        assertThat(accepting).isEmpty()
    }

    @Test
    fun `no type a failure record reaches has a text field`() {
        // Walks the whole reachable shape, so a text field added to any type a failure holds shows
        // up here even when ExtractionFailure itself stays clean.
        reachableFieldTypes(ExtractionFailure::class.java).forEach { type ->
            assertThat(CharSequence::class.java.isAssignableFrom(type))
                .describedAs("%s is text", type.name)
                .isFalse()
        }
    }

    @Test
    fun `every value a populated failure holds is an enum, a number, or an instant`() {
        val populated = ExtractionFailure(
            code = ExtractionFailureCode.SCHEMA_VIOLATION,
            stage = ExtractionFailureStage.SCHEMA_CHECK,
            providerStatus = 422,
            measure = ExtractionFailureMeasure(ExtractionFailureQuantity.TOKEN_COUNT, 4_096),
            at = Instant.parse("2026-08-31T10:15:47Z"),
            invocation = ExtractionInvocationId(invocationIndex = 3, attempt = 2),
        )

        val held = reachableValues(populated)

        assertThat(held).isNotEmpty()
        held.forEach { value ->
            assertThat(value is Enum<*> || value is Number || value is Instant)
                .describedAs("%s is a vocabulary value", value)
                .isTrue()
        }
    }

    @Test
    fun `the closed vocabulary is small enough to read`() {
        // The failure record says everything it says with these. A code that keeps landing on
        // UNCLASSIFIED, or a stage nobody can pick, is the signal to add a value here — and adding
        // one is a reviewed change to a fixed list.
        assertThat(ExtractionFailureCode.entries).hasSize(11)
        assertThat(ExtractionFailureStage.entries).hasSize(10)
        assertThat(ExtractionFailureQuantity.entries).hasSize(7)

        // Every quantity names its unit, so 30000 can never be read as seconds by one caller and
        // millis by another.
        assertThat(ExtractionFailureQuantity.entries.map { it.name })
            .allMatch { it.endsWith("_COUNT") || it.endsWith("_MILLIS") || it.endsWith("_SECONDS") }
    }

    @Test
    fun `the protected content reference ships as specification only`() {
        // It is there so a host writing its own detail vault has a written contract to work from.
        // DICE implementing one would make DICE the writer, the reader and the retention owner,
        // which is the whole of what the contract hands to the host.
        assertThat(ProtectedContentRef::class.java.isInterface).isTrue()
        assertThat(ProtectedContentRef::class.java.declaredMethods)
            .allMatch { Modifier.isAbstract(it.modifiers) }

        val mentions = compiledMainClasses()
            .filter { it.name.substringBefore('.') != "ProtectedContentRef" }
            .filter { String(it.readBytes(), Charsets.ISO_8859_1).contains("ProtectedContentRef") }
            .map { it.name }

        // A class file mentions a type it implements, holds, or calls. KDoc pointing a reader at
        // the specification compiles to nothing, so this stays empty while the docs still link it.
        assertThat(mentions).isEmpty()
    }

    /** Every compiled class of the DICE main source set, read as bytes. */
    private fun compiledMainClasses(): List<File> {
        val root = File(ExtractionFailure::class.java.protectionDomain.codeSource.location.toURI())
        return root.walkTopDown().filter { it.isFile && it.extension == "class" }.toList()
    }

    /** Constructors and public methods: every way a caller can hand a value in. */
    private fun entryPointsOf(vararg types: Class<*>): List<Executable> =
        types.flatMap { type ->
            val nested = type.declaredClasses.flatMap { it.declaredMethods.asList() }
            (type.declaredConstructors.asList() + type.declaredMethods.asList() + nested)
                .filter { Modifier.isPublic(it.modifiers) && !it.isSynthetic }
                // equals takes Object, which every value is assignable to, so it says nothing here.
                .filter { it.name != "equals" }
        }

    /** Field types reachable from [root], recursing into DICE's own types. */
    private fun reachableFieldTypes(root: Class<*>): Set<Class<*>> {
        val seen = mutableSetOf<Class<*>>()
        val types = mutableSetOf<Class<*>>()
        fun walk(type: Class<*>) {
            if (!seen.add(type)) return
            instanceFields(type).forEach { field ->
                types += field.type
                if (field.type.name.startsWith("com.embabel.")) walk(field.type)
            }
        }
        walk(root)
        return types
    }

    /** Every non-null value reachable from [root], flattened, recursing into DICE's own types. */
    private fun reachableValues(root: Any): List<Any> {
        val values = mutableListOf<Any>()
        fun walk(value: Any) {
            instanceFields(value.javaClass).forEach { field ->
                field.isAccessible = true
                val held = field.get(value) ?: return@forEach
                if (held.javaClass.name.startsWith("com.embabel.") && held !is Enum<*>) {
                    walk(held)
                } else {
                    values += held
                }
            }
        }
        walk(root)
        return values
    }

    private fun instanceFields(type: Class<*>): List<Field> =
        type.declaredFields.filterNot { it.isSynthetic || Modifier.isStatic(it.modifiers) }

    companion object {

        /**
         * The four kinds of text the reviewer named, each in the shape it really arrives in.
         *
         * The credential shapes are the ones a scanner recognises: an AWS access key id and an
         * OpenAI-style project key. They earn a line of their own because a character-class check
         * would let the AWS one through — it is all uppercase letters and digits — while a type
         * with no string field lets neither through.
         */
        private val LEAK_CANDIDATES: List<Pair<String, Any>> = listOf(
            "raw source text" to ExtractionRunFixtures.SOURCE_TEXT,
            "a prompt fragment" to "You are an extraction assistant. Extract every claim about: <document>",
            "an email address" to "marguerite.okonkwo@acme-holdings.example",
            "an AWS-shaped access key id" to "AKIAIOSFODNN7EXAMPLE",
            "an OpenAI-shaped project key" to "sk-proj-8f2a10bd4c7e9013a5b6c8d2e4f60719",
        )
    }
}
