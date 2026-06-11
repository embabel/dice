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
package com.embabel.dice.bundle

import java.io.OutputStream
import java.io.Writer

/**
 * SPI for serialising a [KnowledgeBundle] to an external representation.
 *
 * The default implementation ([support.JacksonKnowledgeBundleExporter]) uses Jackson JSON.
 * Callers that need a different format (e.g., CBOR, Protobuf) provide their own implementation.
 */
interface KnowledgeBundleExporter {

    /**
     * Serialise [bundle] to a self-contained JSON string.
     *
     * @param bundle The bundle to serialise.
     * @return A UTF-8 string representation of the bundle.
     */
    fun exportToString(bundle: KnowledgeBundle): String

    /**
     * Serialise [bundle], writing the result to [outputStream].
     * The stream is flushed before this method returns but is NOT closed —
     * the caller is responsible for closing it.
     *
     * @param bundle The bundle to serialise.
     * @param outputStream Target stream. Caller is responsible for closing.
     */
    fun exportToStream(bundle: KnowledgeBundle, outputStream: OutputStream)

    /**
     * Serialise [bundle], writing the result to [writer].
     * The writer is flushed before this method returns but is NOT closed —
     * the caller is responsible for closing it.
     *
     * @param bundle The bundle to serialise.
     * @param writer Target writer. Caller is responsible for closing.
     */
    fun exportToWriter(bundle: KnowledgeBundle, writer: Writer)
}
