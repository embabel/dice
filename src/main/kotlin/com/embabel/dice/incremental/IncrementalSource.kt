package com.embabel.dice.incremental

/**
 * Represents a source of items that grows over time.
 * Used for incremental processing of conversations, listening history, etc.
 *
 * The source provides indexed access to items, allowing windowed processing
 * with overlap for context preservation.
 *
 * @param T The type of items in the source
 */
interface IncrementalSource<T> {

    /**
     * Unique identifier for this source.
     * Used to track processing history across sessions.
     */
    val id: String

    /**
     * Current number of items in the source.
     */
    val size: Int

    /**
     * Get items in the specified range.
     *
     * @param start Start index (inclusive)
     * @param end End index (exclusive)
     * @return List of items in the range
     */
    fun getItems(start: Int, end: Int): List<T>
}

/**
 * Formats items from an IncrementalSource into text for processing.
 *
 * @param T The type of items to format
 */
interface IncrementalSourceFormatter<T> {

    /**
     * Format a list of items into text suitable for proposition extraction.
     *
     * @param items The items to format
     * @return Formatted text representation
     */
    fun format(items: List<T>): String
}
