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
package com.embabel.dice.apilisting

import com.embabel.dice.apilisting.fixture.Stable
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import java.time.Duration
import kotlin.io.path.copyTo
import kotlin.io.path.createDirectories
import kotlin.io.path.readText
import kotlin.io.path.writeText

class ApiListingTest {

    @TempDir
    lateinit var dir: Path

    private val api by lazy { dir.resolve("api") }
    private val out = ByteArrayOutputStream()
    private val err = ByteArrayOutputStream()

    @Test
    fun `check passes right after update`() {
        assertEquals(0, listing("update"))
        assertEquals(0, listing("check"), err.toString())
    }

    @Test
    fun `a facade whose functions are all experimental is left out`() {
        listing("update")
        assertFalse(api.resolve("m.api").readText().contains("/ExperimentalFunctionsKt {"))
    }

    @Test
    fun `a facade with one plain function stays in the listing`() {
        listing("update")
        assertTrue(api.resolve("m.api").readText().contains("/MixedFunctionsKt {"))
    }

    @Test
    fun `an experimental class is left out with its nested class and companion`() {
        listing("update")
        val listed = api.resolve("m.api").readText()
        listOf("/Trial {", "/Trial\$Step {", "/Trial\$Companion {").forEach { assertFalse(listed.contains(it), it) }
    }

    @Test
    fun `an experimental member keeps its class and itself in the listing`() {
        listing("update")
        val listed = api.resolve("m.api").readText()
        assertTrue(listed.contains("/Settled {"), listed)
        assertTrue(listed.contains("tryOut"), listed)
    }

    @Test
    fun `update writes no experimental listing`() {
        assertEquals(0, listing("update"))
        Files.list(api).use { files -> assertEquals(listOf("m.api"), files.map { it.fileName.toString() }.toList()) }
    }

    @Test
    fun `check fails on a leftover experimental listing and says to delete it`() {
        listing("update")
        val leftover = api.resolve("m.experimental.api")
        leftover.writeText("public final class a/B {\n}\n")

        assertEquals(1, listing("check"))
        assertTrue(err.toString().contains("$leftover is no longer used"), err.toString())
        assertTrue(err.toString().contains("Delete it"), err.toString())
        assertTrue(Files.exists(leftover))
    }

    @Test
    fun `update deletes a leftover experimental listing`() {
        val leftover = api.createDirectories().resolve("m.experimental.api")
        leftover.writeText("public final class a/B {\n}\n")

        assertEquals(0, listing("update"), err.toString())
        assertFalse(Files.exists(leftover))
        assertTrue(out.toString().contains("Deleted $leftover"), out.toString())
        assertEquals(0, listing("check"), err.toString())
    }

    @Test
    fun `a listing with Windows line endings shows only the line that really changed`() {
        listing("update")
        val stable = api.resolve("m.api")
        stable.writeText(stable.readText().replace("longestFirst", "shortestFirst").replace("\n", "\r\n"))

        assertEquals(1, listing("check"))
        val changed = err.toString().lines().filter { it.startsWith("-\t") || it.startsWith("+\t") }
        assertEquals(2, changed.size, err.toString())
    }

    @Test
    fun `classes compiled from a generated source root are left out`() {
        val generated = dir.resolve("generated")
        generated.resolve(FIXTURE_PACKAGE).createDirectories().resolve("Stable.kt").writeText("")

        assertEquals(0, listing("update", "--generated=$generated"), err.toString())
        val stable = api.resolve("m.api").readText()
        assertFalse(stable.contains("/Stable {"))
        assertTrue(stable.contains("/MixedFunctionsKt {"))
    }

    @Test
    fun `nested, companion and facade classes of a generated file are left out`() {
        val types = listOf("/GeneratedModel {", "/GeneratedModel\$Nested {", "/GeneratedModel\$Companion {", "/GeneratedModelKt {")
        listing("update")
        val everything = api.resolve("m.api").readText()
        types.forEach { assertTrue(everything.contains(it), it) }

        val generated = dir.resolve("generated")
        generated.resolve(FIXTURE_PACKAGE).createDirectories().resolve("GeneratedModel.kt").writeText("")
        assertEquals(0, listing("update", "--generated=$generated"), err.toString())
        val listed = api.resolve("m.api").readText()
        types.forEach { assertFalse(listed.contains(it), it) }
        assertTrue(listed.contains("/Stable {"))
    }

    @Test
    fun `a source file under both the generated and a hand-written root stops the program`() {
        val generated = dir.resolve("generated")
        generated.resolve(FIXTURE_PACKAGE).createDirectories().resolve("Stable.kt").writeText("")

        assertEquals(2, listing("update", "--generated=$generated", sourceRoot("Stable.kt")))
        assertTrue(err.toString().contains("$FIXTURE_PACKAGE/Stable.kt is both generated and hand-written"), err.toString())
    }

    @Test
    fun `a hand-written file that declares the generated file's package stops the program from any directory`() {
        val generated = dir.resolve("generated")
        generated.resolve(FIXTURE_PACKAGE).createDirectories().resolve("Stable.kt").writeText("")
        val handWritten = dir.resolve("src")
        handWritten.resolve("elsewhere").createDirectories().resolve("Stable.kt")
            .writeText("@file:JvmName(\"Other\")\n\npackage ${FIXTURE_PACKAGE.replace('/', '.')}\n")

        assertEquals(2, listing("update", "--generated=$generated", "--source=$handWritten"))
        assertTrue(err.toString().contains("$FIXTURE_PACKAGE/Stable.kt is both generated and hand-written"), err.toString())
    }

    @Test
    fun `a hand-written file of the same name in another package is not a clash`() {
        val generated = dir.resolve("generated")
        generated.resolve(FIXTURE_PACKAGE).createDirectories().resolve("Stable.kt").writeText("")
        val handWritten = dir.resolve("src")
        handWritten.resolve("other").createDirectories().resolve("Stable.kt").writeText("package other\n")

        assertEquals(0, listing("update", "--generated=$generated", "--source=$handWritten"), err.toString())
        assertFalse(api.resolve("m.api").readText().contains("/Stable {"))
    }

    @Test
    fun `an empty generated source root leaves every class in`() {
        assertEquals(0, listing("update", "--generated="), err.toString())
        assertTrue(api.resolve("m.api").readText().contains("/Stable {"))
    }

    @Test
    fun `a real API change does not blame an earlier build`() {
        val sources = sourceRoot("Stable.kt", "ExperimentalFunctions.kt", "MixedFunctions.kt", "GeneratedModel.kt")
        listing("update", sources)
        val stable = api.resolve("m.api")
        stable.writeText(stable.readText().replace("longestFirst", "shortestFirst"))

        assertEquals(1, listing("check", sources))
        assertFalse(err.toString().contains("earlier build"), err.toString())
    }

    @Test
    fun `a class whose source file is gone is named as left from an earlier build`() {
        listing("update")
        val stable = api.resolve("m.api")
        stable.writeText(stable.readText().replace(Regex("public final class [^\\n]*/MixedFunctionsKt \\{[^}]*}\\n\\n"), ""))

        assertEquals(1, listing("check", sourceRoot("Stable.kt", "ExperimentalFunctions.kt")))
        val hint = err.toString().lines().filter { it.contains("earlier build") }
        assertTrue(hint.any { it.contains("$FIXTURE_PACKAGE/MixedFunctionsKt") }, err.toString())
    }

    @Test
    fun `a class whose file is gone is named even when another package has a file of that name`() {
        listing("update")
        val stable = api.resolve("m.api")
        stable.writeText(stable.readText().replace(Regex("public final class [^\\n]*/MixedFunctionsKt \\{[^}]*}\\n\\n"), ""))
        val sources = sourceRoot("Stable.kt", "ExperimentalFunctions.kt", "GeneratedModel.kt")
        dir.resolve("src/other").createDirectories().resolve("MixedFunctions.kt").writeText("package other\n")

        assertEquals(1, listing("check", sources))
        val hint = err.toString().lines().filter { it.contains("earlier build") }
        assertTrue(hint.any { it.contains("$FIXTURE_PACKAGE/MixedFunctionsKt") }, err.toString())
    }

    @Test
    fun `a class the compiler no longer writes from a file that still exists is named`() {
        val classes = fixtureClasses().resolve(FIXTURE_PACKAGE)
        val facade = classes.resolve("GeneratedModelKt.class")
        Files.setLastModifiedTime(facade, FileTime.from(Files.getLastModifiedTime(facade).toInstant().minus(Duration.ofHours(1))))
        val sources = sourceRoot("Stable.kt", "ExperimentalFunctions.kt", "MixedFunctions.kt", "GeneratedModel.kt")
        listing("update", sources)
        val stable = api.resolve("m.api")
        stable.writeText(stable.readText().replace(Regex("public final class [^\\n]*/GeneratedModelKt \\{[^}]*}\\n\\n"), ""))

        assertEquals(1, listing("check", sources))
        val hint = err.toString().lines().single { it.contains("earlier build") }
        assertEquals(listOf("$FIXTURE_PACKAGE/GeneratedModelKt"), hint.substringAfter("them: ").split(", "))
    }

    @Test
    fun `without hand-written source roots no class is blamed for a missing file`() {
        val generated = "--generated=" + dir.resolve("generated")
        listing("update", generated)
        val stable = api.resolve("m.api")
        stable.writeText(stable.readText().replace("longestFirst", "shortestFirst"))

        assertEquals(1, listing("check", generated))
        assertFalse(err.toString().contains("earlier build"), err.toString())
    }

    @Test
    fun `a passing check reads no class file`() {
        val classes = fixtureClasses()
        classes.resolve(FIXTURE_PACKAGE).resolve("Unreadable.class").writeText("not a class file")
        val dump = "public final class a/B {\n}\n"
        api.createDirectories().resolve("m.api").writeText(dump)
        val args = arrayOf("check", classes.toString(), api.toString(), "m", sourceRoot("Stable.kt"))

        assertEquals(0, ApiListing.run(args, PrintStream(out), PrintStream(err)) { _, _ -> dump }, err.toString())
    }

    @Test
    fun `a failure inside the ABI tools keeps its stack trace`() {
        val args = arrayOf("check", fixtureClasses().toString(), api.toString(), "m")
        val failure = IllegalStateException("cannot read metadata")

        val thrown = assertThrows(IllegalStateException::class.java) {
            ApiListing.run(args, PrintStream(out), PrintStream(err)) { _, _ -> throw failure }
        }
        assertSame(failure, thrown)
    }

    @Test
    fun `whitespace at the end of a listing does not count as a change`() {
        listing("update")
        val stable = api.resolve("m.api")
        val text = stable.readText().trimEnd()
        for (ending in listOf("", "\n", "\n\n\n", "\r\n")) {
            stable.writeText(text + ending)
            assertEquals(0, listing("check"), "ending " + ending.length + ": " + err)
        }
    }

    @Test
    fun `update leaves a listing alone when check accepts it`() {
        listing("update")
        val stable = api.resolve("m.api")
        val windows = stable.readText().trimEnd().replace("\n", "\r\n") + "\r\n\r\n"
        stable.writeText(windows)
        out.reset()

        assertEquals(0, listing("check"))
        assertEquals(0, listing("update"))
        assertEquals("", out.toString())
        assertEquals(windows, stable.readText())
    }

    @Test
    fun `update ends a listing with one newline`() {
        listing("update")
        val text = api.resolve("m.api").readText()
        assertTrue(text.endsWith("}\n") && !text.endsWith("\n\n"))
    }

    @Test
    fun `an unreadable dump stops the program with a message`() {
        val args = arrayOf("check", fixtureClasses().toString(), api.toString(), "m")
        val status = ApiListing.run(args, PrintStream(ByteArrayOutputStream()), PrintStream(err)) { _, _ ->
            "public final object a/B {\n}\n"
        }

        assertEquals(2, status)
        assertTrue(err.toString().contains("Cannot read a type name from the listing line: public final object a/B {"))
    }

    @Test
    fun `a listing line without a class keyword is rejected`() {
        assertThrows(IllegalStateException::class.java) { ApiListing.typeName("public final object a/B {") }
    }

    /**
     * Runs the program over the fixture classes as a module named `m`.
     *
     * @param mode check or update
     * @param options extra arguments after the module name
     * @return the exit status
     */
    private fun listing(mode: String, vararg options: String): Int {
        val args = arrayOf(mode, fixtureClasses().toString(), api.toString(), "m", *options)
        return ApiListing.run(args, PrintStream(out), PrintStream(err))
    }

    /**
     * Writes empty hand-written source files into a source root.
     *
     * @param names the file names
     * @return the `--source` argument for the root
     */
    private fun sourceRoot(vararg names: String): String {
        val root = dir.resolve("src").resolve(FIXTURE_PACKAGE).createDirectories()
        names.forEach { root.resolve(it).writeText("") }
        return "--source=" + dir.resolve("src")
    }

    /**
     * Copies the compiled fixture classes into a directory of their own, so the program sees nothing else.
     *
     * @return the directory holding the copies
     */
    private fun fixtureClasses(): Path {
        val classes = dir.resolve("classes")
        val target = classes.resolve(FIXTURE_PACKAGE)
        if (Files.notExists(target)) {
            val testClasses = Path.of(Stable::class.java.protectionDomain.codeSource.location.toURI())
            target.createDirectories()
            Files.list(testClasses.resolve(FIXTURE_PACKAGE)).use { files ->
                files.forEach { it.copyTo(target.resolve(it.fileName)) }
            }
        }
        return classes
    }

    private companion object {
        const val FIXTURE_PACKAGE = "com/embabel/dice/apilisting/fixture"
    }
}
