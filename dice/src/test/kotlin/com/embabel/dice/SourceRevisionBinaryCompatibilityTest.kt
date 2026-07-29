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

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.asm.ClassReader
import org.springframework.asm.ClassVisitor
import org.springframework.asm.ClassWriter
import org.springframework.asm.MethodVisitor
import org.springframework.asm.Opcodes
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.net.URLClassLoader
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.jar.JarFile

class SourceRevisionBinaryCompatibilityTest {

    @Test
    fun `pinned legacy client links only the approved compatibility boundary`() {
        val source = requireResource(FIXTURE_SOURCE)
        val clientJar = requireResource(FIXTURE_JAR)

        assertEquals(FIXTURE_SOURCE_SHA256, sha256(Files.readAllBytes(source)))
        assertEquals(FIXTURE_JAR_SHA256, sha256(Files.readAllBytes(clientJar)))
        assertFixtureJar(clientJar)

        val linkedOutput = runClient(clientJar, javaClass.classLoader)
        assertEquals(EXPECTED_CANDIDATE_OUTPUT, linkedOutput)
        println("legacy-client candidate output:")
        linkedOutput.forEach(::println)

        val withoutApprovedConstructor = WithoutLegacyProvenanceConstructor(javaClass.classLoader)
        val negativeOutput = runClient(clientJar, withoutApprovedConstructor)
        assertEquals(1, withoutApprovedConstructor.removedConstructors)
        assertTrue(
            "ProvenanceEntry.constructor.full=NoSuchMethodError" in negativeOutput,
            "Removing the approved constructor must break the same legacy call site: $negativeOutput",
        )
        println("legacy-client negative-control output:")
        negativeOutput.forEach(::println)
    }

    private fun assertFixtureJar(clientJar: Path) {
        JarFile(clientJar.toFile()).use { jar ->
            assertEquals(
                setOf(
                    "META-INF/",
                    "META-INF/MANIFEST.MF",
                    "com/embabel/dice/compat/",
                    "com/embabel/dice/compat/Source_revision_legacy_clientKt.class",
                ),
                jar.entries().asSequence().map { it.name }.toSet(),
            )
            assertEquals(PINNED_BASE_SHA, jar.manifest.mainAttributes.getValue("Dice-Base-SHA"))
            assertEquals(
                FIXTURE_SOURCE_SHA256,
                jar.manifest.mainAttributes.getValue("Fixture-Source-SHA256"),
            )
        }
    }

    private fun runClient(clientJar: Path, parent: ClassLoader): List<String> {
        val bytes = ByteArrayOutputStream()
        val originalOut = System.out
        try {
            PrintStream(bytes, true, StandardCharsets.UTF_8).use { captured ->
                System.setOut(captured)
                URLClassLoader(arrayOf(clientJar.toUri().toURL()), parent).use { loader ->
                    loader
                        .loadClass(CLIENT_MAIN_CLASS)
                        .getMethod("main")
                        .invoke(null)
                }
            }
        } finally {
            System.setOut(originalOut)
        }
        return bytes
            .toString(StandardCharsets.UTF_8)
            .lineSequence()
            .filter(String::isNotBlank)
            .toList()
    }

    private fun requireResource(name: String): Path =
        Path.of(requireNotNull(javaClass.classLoader.getResource(name)) { "Missing resource $name" }.toURI())

    private fun sha256(bytes: ByteArray): String =
        MessageDigest
            .getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { "%02x".format(it) }

    private class WithoutLegacyProvenanceConstructor(
        parent: ClassLoader,
    ) : ClassLoader(parent) {

        var removedConstructors: Int = 0
            private set

        override fun loadClass(name: String, resolve: Boolean): Class<*> {
            if (name != PROVENANCE_ENTRY_CLASS) {
                return super.loadClass(name, resolve)
            }
            synchronized(getClassLoadingLock(name)) {
                var loaded = findLoadedClass(name)
                if (loaded == null) {
                    loaded = defineWithoutLegacyConstructor(name)
                }
                if (resolve) {
                    resolveClass(loaded)
                }
                return loaded
            }
        }

        private fun defineWithoutLegacyConstructor(name: String): Class<*> {
            val resource = name.replace('.', '/') + ".class"
            val candidateBytes =
                requireNotNull(parent.getResourceAsStream(resource)) {
                    "Candidate bytecode is missing $resource"
                }.use { it.readAllBytes() }
            val reader = ClassReader(candidateBytes)
            val writer = ClassWriter(reader, 0)
            reader.accept(
                object : ClassVisitor(Opcodes.ASM9, writer) {
                    override fun visitMethod(
                        access: Int,
                        name: String,
                        descriptor: String,
                        signature: String?,
                        exceptions: Array<out String>?,
                    ): MethodVisitor? {
                        if (name == "<init>" && descriptor == LEGACY_PROVENANCE_CONSTRUCTOR) {
                            removedConstructors += 1
                            return null
                        }
                        return super.visitMethod(access, name, descriptor, signature, exceptions)
                    }
                },
                0,
            )
            val transformed = writer.toByteArray()
            return defineClass(name, transformed, 0, transformed.size)
        }
    }

    private companion object {
        const val PINNED_BASE_SHA = "c769d9c479d3e90c5c23c88343c79bd31e70a78f"
        const val FIXTURE_SOURCE_SHA256 =
            "e79637cdef1428bc7be5239c19caa18e3b4b1fe191baa8b6c5723bb9a814dc76"
        const val FIXTURE_JAR_SHA256 =
            "25d25191e7eb46e7e99bf10c02c5b4a4d7c3f7b511a7cbcf6c8c1b442c86590a"
        const val FIXTURE_SOURCE = "compat/source-revision-legacy-client.kt"
        const val FIXTURE_JAR = "compat/source-revision-legacy-client.jar"
        const val CLIENT_MAIN_CLASS = "com.embabel.dice.compat.Source_revision_legacy_clientKt"
        const val PROVENANCE_ENTRY_CLASS = "com.embabel.dice.provenance.ProvenanceEntry"
        const val LEGACY_PROVENANCE_CONSTRUCTOR =
            "(Lcom/embabel/dice/provenance/SourceLocator;Ljava/lang/String;" +
                "Ljava/lang/Integer;Ljava/lang/Integer;Ljava/lang/String;)V"

        val EXPECTED_CANDIDATE_OUTPUT =
            listOf(
                "ProvenanceEntry.constructor.full=LINKED",
                "ProvenanceEntry.constructor.nullable=LINKED",
                "ProvenanceEntry.copy.direct=NoSuchMethodError",
                "ProvenanceEntry.copy.default=NoSuchMethodError",
                "SourceAnalysisContext.constructor.full=LINKED",
                "SourceAnalysisContext.constructor.alternate=LINKED",
                "SourceAnalysisContext.copy.direct=NoSuchMethodError",
                "SourceAnalysisContext.copy.default=NoSuchMethodError",
            )
    }
}
