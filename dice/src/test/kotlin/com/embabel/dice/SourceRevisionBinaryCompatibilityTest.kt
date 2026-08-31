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

        // Negative controls, one per probed type. Each removes the approved constructor from the
        // candidate bytecode and checks the matching probe flips to NoSuchMethodError, so a green
        // run means the probe is measuring that constructor rather than merely finding the class.
        // They are separate loaders so each control leaves the other type intact, and the untouched
        // probes are asserted to stay LINKED.
        val withoutProvenanceConstructor = WithoutConstructor(
            javaClass.classLoader,
            PROVENANCE_ENTRY_CLASS,
            LEGACY_PROVENANCE_CONSTRUCTOR,
        )
        val provenanceNegative = runClient(clientJar, withoutProvenanceConstructor)
        assertEquals(1, withoutProvenanceConstructor.removedConstructors)
        assertTrue(
            "ProvenanceEntry.constructor.full=NoSuchMethodError" in provenanceNegative,
            "Removing the approved constructor must break the same legacy call site: $provenanceNegative",
        )
        assertTrue(
            "SourceAnalysisContext.constructor.full=LINKED" in provenanceNegative,
            "The context probe must be unaffected by the ProvenanceEntry control: $provenanceNegative",
        )
        println("legacy-client negative-control output (ProvenanceEntry):")
        provenanceNegative.forEach(::println)

        val withoutContextConstructor = WithoutConstructor(
            javaClass.classLoader,
            SOURCE_ANALYSIS_CONTEXT_CLASS,
            legacyContextConstructorDescriptor(clientJar),
        )
        val contextNegative = runClient(clientJar, withoutContextConstructor)
        assertEquals(1, withoutContextConstructor.removedConstructors)
        assertTrue(
            "SourceAnalysisContext.constructor.full=NoSuchMethodError" in contextNegative,
            "Removing the approved constructor must break the same legacy call site: $contextNegative",
        )
        assertTrue(
            "ProvenanceEntry.constructor.full=LINKED" in contextNegative,
            "The ProvenanceEntry probe must be unaffected by the context control: $contextNegative",
        )
        println("legacy-client negative-control output (SourceAnalysisContext):")
        contextNegative.forEach(::println)
    }

    /**
     * The `SourceAnalysisContext` constructor descriptor the pinned client actually links against,
     * read out of the client's own bytecode.
     *
     * Taken from the jar rather than from reflection on purpose. `ContextId` is a value class, so
     * the constructor a Kotlin caller writes with ten arguments compiles to an eleven-parameter
     * descriptor: the context id erases to `String`, and a trailing `DefaultConstructorMarker`
     * separates this constructor from the one that erasure would otherwise collide with. Neither
     * fact is evident from the Kotlin signature, and reflection reports a different ten-parameter
     * constructor that the client never calls — so a control built from reflection would strip a
     * constructor nobody was using and pass while proving nothing.
     */
    private fun legacyContextConstructorDescriptor(clientJar: Path): String {
        val owner = SOURCE_ANALYSIS_CONTEXT_CLASS.replace('.', '/')
        val descriptors = mutableSetOf<String>()
        JarFile(clientJar.toFile()).use { jar ->
            val entry = requireNotNull(jar.getJarEntry(CLIENT_MAIN_RESOURCE)) {
                "Fixture jar is missing $CLIENT_MAIN_RESOURCE"
            }
            val bytes = jar.getInputStream(entry).use { it.readAllBytes() }
            ClassReader(bytes).accept(
                object : ClassVisitor(Opcodes.ASM9) {
                    override fun visitMethod(
                        access: Int,
                        name: String,
                        descriptor: String,
                        signature: String?,
                        exceptions: Array<out String>?,
                    ): MethodVisitor =
                        object : MethodVisitor(Opcodes.ASM9) {
                            override fun visitMethodInsn(
                                opcode: Int,
                                insnOwner: String,
                                insnName: String,
                                insnDescriptor: String,
                                isInterface: Boolean,
                            ) {
                                if (insnOwner == owner && insnName == "<init>") {
                                    descriptors += insnDescriptor
                                }
                            }
                        }
                },
                0,
            )
        }
        // Both context probes call the same constructor with different values, so one descriptor.
        return descriptors.single()
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

    /**
     * Redefines one class with one constructor removed, and delegates everything else to the parent.
     * Only the named class is redefined, so its parameter types still resolve to the parent's
     * classes and the rest of the fixture's probes run against untouched bytecode.
     */
    private class WithoutConstructor(
        parent: ClassLoader,
        private val targetClass: String,
        private val targetDescriptor: String,
    ) : ClassLoader(parent) {

        var removedConstructors: Int = 0
            private set

        override fun loadClass(name: String, resolve: Boolean): Class<*> {
            if (name != targetClass) {
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
                        if (name == "<init>" && descriptor == targetDescriptor) {
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
            "8a2ce95b81d59303d5361db85e60074249652f73f4d6890c89499d940a29e524"
        const val FIXTURE_JAR_SHA256 =
            "505cb11891a051f69cefe70e3a0862e29e66e78df864cae2aac2a9c2eddf08a2"
        const val FIXTURE_SOURCE = "compat/source-revision-legacy-client.kt"
        const val FIXTURE_JAR = "compat/source-revision-legacy-client.jar"
        const val CLIENT_MAIN_CLASS = "com.embabel.dice.compat.Source_revision_legacy_clientKt"
        const val CLIENT_MAIN_RESOURCE = "com/embabel/dice/compat/Source_revision_legacy_clientKt.class"
        const val PROVENANCE_ENTRY_CLASS = "com.embabel.dice.provenance.ProvenanceEntry"
        const val SOURCE_ANALYSIS_CONTEXT_CLASS = "com.embabel.dice.common.SourceAnalysisContext"
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
