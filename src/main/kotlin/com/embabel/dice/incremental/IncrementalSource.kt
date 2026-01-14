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
