package com.embabel.dice.proposition.content

import com.embabel.agent.rag.model.Chunk
import java.time.Instant

/**
 * Adapts a document [Chunk] to [ProposableContent].
 *
 * Chunks are pieces of text from documents that can be processed
 * to extract propositions. This adapter enables the same proposition
 * extraction pipeline to work on both sensor observations and document chunks.
 *
 * @param chunk The source chunk
 * @param timestamp When the chunk was created/processed (defaults to now if not in metadata)
 * @param documentContext Optional context about the source document
 */
data class ChunkContent(
    val chunk: Chunk,
    override val timestamp: Instant = extractTimestamp(chunk),
    val documentContext: DocumentContext? = null,
) : ProposableContent {

    override val sourceId: String get() = chunk.id

    override fun toProposalContext(): String = buildString {
        appendLine("=== Document Chunk ===")

        // Add document context if available
        documentContext?.let { ctx ->
            ctx.title?.let { appendLine("Document: $it") }
            ctx.source?.let { appendLine("Source: $it") }
            ctx.section?.let { appendLine("Section: $it") }
        }

        // Add metadata context if available
        chunk.metadata["title"]?.let { appendLine("Document: $it") }
        chunk.metadata["source"]?.let { appendLine("Source: $it") }
        chunk.uri?.let { appendLine("URI: $it") }

        appendLine()
        appendLine("Content:")
        appendLine(chunk.text)
    }

    override fun isProcessable(): Boolean = chunk.text.isNotBlank()

    companion object {
        private fun extractTimestamp(chunk: Chunk): Instant {
            // Try to get timestamp from metadata
            val timestampMeta = chunk.metadata["timestamp"]
                ?: chunk.metadata["created_at"]
                ?: chunk.metadata["extracted_at"]

            return when (timestampMeta) {
                is Instant -> timestampMeta
                is Long -> Instant.ofEpochMilli(timestampMeta)
                is String -> runCatching { Instant.parse(timestampMeta) }.getOrNull() ?: Instant.now()
                else -> Instant.now()
            }
        }
    }
}

/**
 * Optional context about the source document.
 */
data class DocumentContext(
    val title: String? = null,
    val source: String? = null,
    val section: String? = null,
    val metadata: Map<String, Any?> = emptyMap(),
)

/**
 * Extension function to convert a Chunk to ProposableContent.
 */
fun Chunk.toProposableContent(
    timestamp: Instant = Instant.now(),
    documentContext: DocumentContext? = null,
): ProposableContent = ChunkContent(
    chunk = this,
    timestamp = timestamp,
    documentContext = documentContext,
)

/**
 * Extension function to convert a list of Chunks to ProposableContent.
 */
fun List<Chunk>.toProposableContent(
    timestamp: Instant = Instant.now(),
    documentContext: DocumentContext? = null,
): List<ProposableContent> = map { it.toProposableContent(timestamp, documentContext) }
