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
package com.embabel.dice.common

/**
 * Strategy for computing a content hash from text.
 * Used for content-based deduplication across the DICE pipeline.
 */
fun interface ContentHasher {

    /**
     * Compute a hash of the given text.
     *
     * @param text The text to hash
     * @return A hash string suitable for equality comparison
     */
    fun hash(text: String): String
}
