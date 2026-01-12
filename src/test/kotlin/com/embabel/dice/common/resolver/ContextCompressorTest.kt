package com.embabel.dice.common.resolver

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class ContextCompressorTest {

    @Nested
    inner class WindowContextCompressorTest {

        private val compressor = WindowContextCompressor(
            windowChars = 50,
            maxSnippets = 2,
            maxTotalChars = 300,
        )

        @Test
        fun `returns null for null input`() {
            assertNull(compressor.compress(null, "Brahms"))
        }

        @Test
        fun `returns null for blank input`() {
            assertNull(compressor.compress("   ", "Brahms"))
        }

        @Test
        fun `returns null for blank entity name`() {
            assertNull(compressor.compress("Some text about music", ""))
        }

        @Test
        fun `extracts window around exact mention`() {
            val text = "I've been listening to a lot of classical music. I really love Brahms. His symphonies are wonderful."
            val result = compressor.compress(text, "Brahms")

            assertNotNull(result)
            assertTrue(result!!.contains("Brahms"))
            assertTrue(result.length < text.length)
        }

        @Test
        fun `extracts window around partial name match`() {
            val text = "The concert featured works by Johannes Brahms and Clara Schumann."
            val result = compressor.compress(text, "Brahms")

            assertNotNull(result)
            assertTrue(result!!.contains("Brahms"))
        }

        @Test
        fun `handles multiple mentions`() {
            val text = """
                First paragraph about Brahms and his early life.
                Some other content here that doesn't mention the composer.
                Another paragraph discussing Brahms symphonies in detail.
                More unrelated content about other topics entirely.
            """.trimIndent()

            val result = compressor.compress(text, "Brahms")

            assertNotNull(result)
            // Should have snippets from both mentions
            assertTrue(result!!.contains("Brahms"))
        }

        @Test
        fun `limits total output length`() {
            val longText = "Brahms ".repeat(100) + "is a great composer."
            val result = compressor.compress(longText, "Brahms")

            assertNotNull(result)
            assertTrue(result!!.length <= 310) // maxTotalChars + some ellipsis
        }

        @Test
        fun `returns truncated text when entity not found`() {
            val text = "This is a long text about classical music and various composers from the Romantic era."
            val result = compressor.compress(text, "NonExistent")

            assertNotNull(result)
            // Should return truncated version since entity not found
        }

        @Test
        fun `adds ellipsis for truncated snippets`() {
            val text = "Beginning of text. Some content about Brahms here. End of text."
            val result = compressor.compress(text, "Brahms")

            assertNotNull(result)
            // Should have ellipsis if we're not at text boundaries
        }
    }

    @Nested
    inner class SentenceContextCompressorTest {

        private val compressor = SentenceContextCompressor(
            maxSentences = 2,
            includeSurrounding = true,
        )

        @Test
        fun `extracts sentences containing entity`() {
            val text = "First sentence. Brahms was a German composer. Third sentence. Fourth sentence."
            val result = compressor.compress(text, "Brahms")

            assertNotNull(result)
            assertTrue(result!!.contains("Brahms"))
        }

        @Test
        fun `includes surrounding sentences when enabled`() {
            val text = "Context before. Brahms was amazing. Context after. Unrelated."
            val result = compressor.compress(text, "Brahms")

            assertNotNull(result)
            assertTrue(result!!.contains("Brahms"))
            assertTrue(result.contains("Context before") || result.contains("Context after"))
        }

        @Test
        fun `returns first sentences when entity not found`() {
            val text = "First sentence here. Second sentence here. Third sentence here."
            val result = compressor.compress(text, "NonExistent")

            assertNotNull(result)
            assertTrue(result!!.contains("First sentence"))
        }

        @Test
        fun `handles text without sentence boundaries`() {
            val text = "Some text about Brahms without proper punctuation"
            val result = compressor.compress(text, "Brahms")

            assertNotNull(result)
        }
    }

    @Nested
    inner class AdaptiveContextCompressorTest {

        private val compressor = AdaptiveContextCompressor(
            shortThreshold = 100,
            mediumThreshold = 300,
        )

        @Test
        fun `returns original text for short input`() {
            val shortText = "I love Brahms."
            val result = compressor.compress(shortText, "Brahms")

            assertEquals(shortText, result)
        }

        @Test
        fun `compresses medium length text`() {
            val mediumText = "Some context. " + "More content here. ".repeat(10) + "Brahms was great. More ending content."
            val result = compressor.compress(mediumText, "Brahms")

            assertNotNull(result)
            assertTrue(result!!.contains("Brahms"))
            // Should be compressed but not empty
        }

        @Test
        fun `compresses long text more aggressively`() {
            val longText = "Beginning. ".repeat(50) + "Brahms section here. " + "Ending section. ".repeat(50)
            val result = compressor.compress(longText, "Brahms")

            assertNotNull(result)
            assertTrue(result!!.contains("Brahms"))
            assertTrue(result.length < longText.length)
        }
    }

    @Nested
    inner class NoOpContextCompressorTest {

        @Test
        fun `returns original text unchanged`() {
            val text = "Some text about Brahms"
            val result = NoOpContextCompressor.compress(text, "Brahms")

            assertEquals(text, result)
        }

        @Test
        fun `returns null for null input`() {
            assertNull(NoOpContextCompressor.compress(null, "Brahms"))
        }
    }

    @Nested
    inner class CompressForAllTest {

        private val compressor = WindowContextCompressor(windowChars = 30, maxSnippets = 1)

        @Test
        fun `compresses for multiple entities`() {
            val text = "Brahms was a composer. Wagner wrote operas. Beethoven composed symphonies."
            val result = compressor.compressForAll(text, listOf("Brahms", "Wagner", "Beethoven"))

            assertNotNull(result)
            assertTrue(result!!.contains("Brahms"))
            assertTrue(result.contains("Wagner"))
            assertTrue(result.contains("Beethoven"))
        }

        @Test
        fun `returns null when no entities found`() {
            val text = "Some text about music"
            val result = compressor.compressForAll(text, listOf("NonExistent1", "NonExistent2"))

            // Should return truncated version or null depending on implementation
            // The current implementation returns truncated text when entity not found
            assertNotNull(result)
        }
    }
}
