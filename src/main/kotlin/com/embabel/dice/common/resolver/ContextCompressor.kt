package com.embabel.dice.common.resolver

/**
 * Compresses source text to extract only the portions relevant to entity resolution.
 *
 * Instead of passing entire conversations or documents to LLM calls,
 * this extracts snippets around entity mentions to reduce token usage
 * while preserving disambiguation context.
 *
 * Example:
 * ```
 * Full context (500 tokens):
 *   "Hello! How are you today? I've been listening to a lot of music lately.
 *    Speaking of which, I really love Brahms. His symphonies are incredible,
 *    especially the Fourth. The way he develops themes... [300 more tokens]"
 *
 * Compressed (50 tokens):
 *   "...I really love Brahms. His symphonies are incredible, especially the Fourth..."
 * ```
 */
interface ContextCompressor {

    /**
     * Compress source text to extract relevant context for entity resolution.
     *
     * @param sourceText The full source text (conversation, document, etc.)
     * @param entityName The entity name to find context for
     * @return Compressed context string, or null if no relevant context found
     */
    fun compress(sourceText: String?, entityName: String): String?

    /**
     * Compress source text for multiple entities at once.
     * May be more efficient than calling [compress] repeatedly.
     *
     * @param sourceText The full source text
     * @param entityNames All entity names to find context for
     * @return Compressed context covering all entities
     */
    fun compressForAll(sourceText: String?, entityNames: List<String>): String? {
        if (sourceText == null) return null
        val snippets = entityNames.mapNotNull { compress(sourceText, it) }
        return if (snippets.isEmpty()) null else snippets.distinct().joinToString(" ... ")
    }

    companion object {
        /**
         * Default compressor with sensible settings.
         */
        @JvmStatic
        fun default(): ContextCompressor = WindowContextCompressor()

        /**
         * No compression - returns original text.
         */
        @JvmStatic
        fun none(): ContextCompressor = NoOpContextCompressor
    }
}

/**
 * No-op compressor that returns text unchanged.
 */
object NoOpContextCompressor : ContextCompressor {
    override fun compress(sourceText: String?, entityName: String): String? = sourceText
}

/**
 * Extracts a window of text around each mention of the entity.
 *
 * @param windowChars Characters before and after each mention to include
 * @param maxSnippets Maximum number of snippets to include
 * @param maxTotalChars Maximum total characters in compressed output
 */
class WindowContextCompressor(
    private val windowChars: Int = 100,
    private val maxSnippets: Int = 3,
    private val maxTotalChars: Int = 500,
) : ContextCompressor {

    override fun compress(sourceText: String?, entityName: String): String? {
        if (sourceText.isNullOrBlank() || entityName.isBlank()) return null

        val mentions = findMentions(sourceText, entityName)
        if (mentions.isEmpty()) {
            // Entity not explicitly mentioned - return a summary instead
            return truncateToSentences(sourceText, maxTotalChars)
        }

        val snippets = mentions.take(maxSnippets).map { range ->
            extractSnippet(sourceText, range)
        }

        val combined = snippets.joinToString(" ... ")
        return if (combined.length > maxTotalChars) {
            combined.take(maxTotalChars) + "..."
        } else {
            combined
        }
    }

    /**
     * Find all mentions of the entity name in the text.
     * Handles partial matches and case-insensitivity.
     */
    private fun findMentions(text: String, entityName: String): List<IntRange> {
        val mentions = mutableListOf<IntRange>()
        val lowerText = text.lowercase()

        // Try full name first
        val lowerName = entityName.lowercase()
        var index = lowerText.indexOf(lowerName)
        while (index >= 0) {
            mentions.add(index until (index + entityName.length))
            index = lowerText.indexOf(lowerName, index + 1)
        }

        // Also try individual significant words (for partial matches like "Brahms" in "Johannes Brahms")
        if (mentions.isEmpty()) {
            val words = entityName.split(Regex("\\s+")).filter { it.length >= 3 }
            for (word in words) {
                val lowerWord = word.lowercase()
                index = lowerText.indexOf(lowerWord)
                while (index >= 0) {
                    // Check word boundaries
                    val beforeOk = index == 0 || !lowerText[index - 1].isLetterOrDigit()
                    val afterOk = index + word.length >= lowerText.length ||
                            !lowerText[index + word.length].isLetterOrDigit()
                    if (beforeOk && afterOk) {
                        mentions.add(index until (index + word.length))
                    }
                    index = lowerText.indexOf(lowerWord, index + 1)
                }
            }
        }

        // Merge overlapping ranges and sort
        return mergeRanges(mentions).sortedBy { it.first }
    }

    /**
     * Extract a snippet around a mention with context window.
     */
    private fun extractSnippet(text: String, mentionRange: IntRange): String {
        val start = maxOf(0, mentionRange.first - windowChars)
        val end = minOf(text.length, mentionRange.last + windowChars)

        // Try to expand to word boundaries
        val adjustedStart = if (start > 0) {
            val spaceIndex = text.lastIndexOf(' ', start)
            if (spaceIndex >= start - 20) spaceIndex + 1 else start
        } else 0

        val adjustedEnd = if (end < text.length) {
            val spaceIndex = text.indexOf(' ', end)
            if (spaceIndex >= 0 && spaceIndex <= end + 20) spaceIndex else end
        } else text.length

        val snippet = text.substring(adjustedStart, adjustedEnd).trim()

        return buildString {
            if (adjustedStart > 0) append("...")
            append(snippet)
            if (adjustedEnd < text.length) append("...")
        }
    }

    /**
     * Merge overlapping ranges.
     */
    private fun mergeRanges(ranges: List<IntRange>): List<IntRange> {
        if (ranges.isEmpty()) return emptyList()

        val sorted = ranges.sortedBy { it.first }
        val merged = mutableListOf<IntRange>()
        var current = sorted.first()

        for (range in sorted.drop(1)) {
            if (range.first <= current.last + windowChars) {
                // Overlapping or close enough - merge
                current = current.first..maxOf(current.last, range.last)
            } else {
                merged.add(current)
                current = range
            }
        }
        merged.add(current)

        return merged
    }

    /**
     * Truncate text to complete sentences within limit.
     */
    private fun truncateToSentences(text: String, maxChars: Int): String {
        if (text.length <= maxChars) return text

        val truncated = text.take(maxChars)
        val lastSentenceEnd = truncated.lastIndexOfAny(charArrayOf('.', '!', '?'))

        return if (lastSentenceEnd > maxChars / 2) {
            truncated.substring(0, lastSentenceEnd + 1)
        } else {
            truncated.substringBeforeLast(' ') + "..."
        }
    }
}

/**
 * Extracts context by finding sentences containing the entity.
 *
 * @param maxSentences Maximum sentences to include
 * @param includeSurrounding Include sentences before/after mention
 */
class SentenceContextCompressor(
    private val maxSentences: Int = 3,
    private val includeSurrounding: Boolean = true,
) : ContextCompressor {

    private val sentencePattern = Regex("[.!?]+\\s+")

    override fun compress(sourceText: String?, entityName: String): String? {
        if (sourceText.isNullOrBlank() || entityName.isBlank()) return null

        val sentences = sourceText.split(sentencePattern).map { it.trim() }.filter { it.isNotEmpty() }
        if (sentences.isEmpty()) return sourceText

        val lowerName = entityName.lowercase()
        val nameWords = entityName.split(Regex("\\s+")).filter { it.length >= 3 }.map { it.lowercase() }

        // Find sentences mentioning the entity
        val mentionIndices = sentences.mapIndexedNotNull { index, sentence ->
            val lowerSentence = sentence.lowercase()
            val hasMention = lowerSentence.contains(lowerName) ||
                    nameWords.any { word -> lowerSentence.contains(word) }
            if (hasMention) index else null
        }

        if (mentionIndices.isEmpty()) {
            // Return first few sentences as general context
            return sentences.take(maxSentences).joinToString(". ") + "."
        }

        // Collect sentences with optional surrounding context
        val selectedIndices = mutableSetOf<Int>()
        for (idx in mentionIndices.take(maxSentences)) {
            if (includeSurrounding && idx > 0) selectedIndices.add(idx - 1)
            selectedIndices.add(idx)
            if (includeSurrounding && idx < sentences.lastIndex) selectedIndices.add(idx + 1)
        }

        return selectedIndices.sorted()
            .take(maxSentences + 2)
            .map { sentences[it] }
            .joinToString(". ") + "."
    }
}

/**
 * Hybrid compressor that uses different strategies based on text length.
 *
 * - Short text (<500 chars): No compression
 * - Medium text (500-2000 chars): Sentence extraction
 * - Long text (>2000 chars): Window extraction
 */
class AdaptiveContextCompressor(
    private val shortThreshold: Int = 500,
    private val mediumThreshold: Int = 2000,
) : ContextCompressor {

    private val sentenceCompressor = SentenceContextCompressor()
    private val windowCompressor = WindowContextCompressor()

    override fun compress(sourceText: String?, entityName: String): String? {
        if (sourceText == null) return null

        return when {
            sourceText.length < shortThreshold -> sourceText
            sourceText.length < mediumThreshold -> sentenceCompressor.compress(sourceText, entityName)
            else -> windowCompressor.compress(sourceText, entityName)
        }
    }
}
