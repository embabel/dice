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
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.copyTo
import kotlin.io.path.createDirectories
import kotlin.io.path.readText
import kotlin.io.path.writeText

class ApiListingTest {

    @TempDir
    lateinit var dir: Path

    private val api by lazy { dir.resolve("api") }
    private val err = ByteArrayOutputStream()

    @Test
    fun `check passes right after update`() {
        assertEquals(0, listing("update"))
        assertEquals(0, listing("check"), err.toString())
    }

    @Test
    fun `a facade whose functions are all experimental goes to the experimental listing`() {
        listing("update")
        assertTrue(api.resolve("m.experimental.api").readText().contains("/ExperimentalFunctionsKt {"))
        assertFalse(api.resolve("m.api").readText().contains("/ExperimentalFunctionsKt {"))
    }

    @Test
    fun `a facade with one plain function stays in the stable listing`() {
        listing("update")
        assertTrue(api.resolve("m.api").readText().contains("/MixedFunctionsKt {"))
        assertFalse(api.resolve("m.experimental.api").readText().contains("/MixedFunctionsKt {"))
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
    fun `a listing line without a class keyword is rejected`() {
        assertThrows(IllegalStateException::class.java) { ApiListing.typeName("public final object a/B {") }
    }

    /**
     * Runs the program over the fixture classes as a module named `m`.
     *
     * @param mode check or update
     * @return the exit status
     */
    private fun listing(mode: String): Int {
        val args = arrayOf(mode, fixtureClasses().toString(), api.toString(), "m")
        return ApiListing.run(args, PrintStream(ByteArrayOutputStream()), PrintStream(err))
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
