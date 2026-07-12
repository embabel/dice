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
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.slf4j.LoggerFactory
import java.io.OutputStream
import java.io.Writer

/**
 * Jackson-backed implementation of [KnowledgeBundleExporter].
 *
 * Uses `jacksonObjectMapper().findAndRegisterModules()` — the same configuration used
 * throughout the library — so that `Instant` fields on propositions serialise correctly.
 *
 * `WRITE_DATES_AS_TIMESTAMPS` is disabled so `Instant` fields serialise as ISO-8601 strings
 * instead of a numeric epoch-seconds-plus-fraction: Jackson's jsr310 module parses that numeric
 * fraction back through a `double`, which can't hold nanosecond precision at real-world epoch
 * magnitudes (~1.7e9 seconds leaves a double roughly 200-plus ns of slack) — a roundtrip of
 * `2025-06-01T08:15:30.123456789Z` came back `.123456700Z`. ISO-8601 strings parse back exactly.
 * Same fix Drivine's own [org.drivine.mapper.Neo4jObjectMapper] applies for the identical reason.
 *
 * The mapper is created once at construction and shared across calls; it is
 * thread-safe after configuration.
 */
class JacksonKnowledgeBundleExporter : KnowledgeBundleExporter {

    private val logger = LoggerFactory.getLogger(JacksonKnowledgeBundleExporter::class.java)

    private val mapper = jacksonObjectMapper()
        .findAndRegisterModules()
        .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)

    override fun exportToString(bundle: KnowledgeBundle): String {
        logger.info(
            "Exporting bundle: {} propositions, context={}",
            bundle.propositions.size,
            bundle.contextId,
        )
        return mapper.writeValueAsString(bundle)
    }

    override fun exportToStream(bundle: KnowledgeBundle, outputStream: OutputStream) {
        logger.info(
            "Exporting bundle to stream: {} propositions, context={}",
            bundle.propositions.size,
            bundle.contextId,
        )
        mapper.writeValue(outputStream, bundle)
        outputStream.flush()
    }

    override fun exportToWriter(bundle: KnowledgeBundle, writer: Writer) {
        logger.info(
            "Exporting bundle to writer: {} propositions, context={}",
            bundle.propositions.size,
            bundle.contextId,
        )
        mapper.writeValue(writer, bundle)
        writer.flush()
    }
}
