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
package com.embabel.dice.bundle.support

import com.embabel.dice.bundle.KnowledgeBundle
import com.embabel.dice.bundle.KnowledgeBundleExporter
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import java.io.OutputStream
import java.io.Writer

/**
 * Jackson-backed implementation of [KnowledgeBundleExporter].
 *
 * Uses `jacksonObjectMapper().findAndRegisterModules()` — the same configuration used
 * throughout the library — so that `Instant` fields on propositions serialise correctly.
 *
 * The mapper is created once at construction and shared across calls; it is
 * thread-safe after configuration.
 */
class JacksonKnowledgeBundleExporter : KnowledgeBundleExporter {

    private val mapper = jacksonObjectMapper().findAndRegisterModules()

    override fun exportToString(bundle: KnowledgeBundle): String =
        mapper.writeValueAsString(bundle)

    override fun exportToStream(bundle: KnowledgeBundle, outputStream: OutputStream) {
        mapper.writeValue(outputStream, bundle)
        outputStream.flush()
    }

    override fun exportToWriter(bundle: KnowledgeBundle, writer: Writer) {
        mapper.writeValue(writer, bundle)
        writer.flush()
    }
}
