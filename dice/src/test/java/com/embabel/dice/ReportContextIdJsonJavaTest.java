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
package com.embabel.dice;

import com.embabel.dice.projection.memory.DreamLoopReport;
import com.embabel.dice.projection.memory.collector.CollectorRunContext;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.fasterxml.jackson.module.kotlin.KotlinModule;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.Clock;
import java.util.Iterator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * Checks that the dream-loop report and the collector run context write their context as a plain
 * {@code contextId} key, whichever mapper Java code uses to serialize them.
 */
class ReportContextIdJsonJavaTest {

    // Jackson cannot reflect into java.time.Clock on current JDKs, so the run context's clock is
    // written as an empty object by this small module, which both mappers below register.
    private static final SimpleModule CLOCK_AS_EMPTY_OBJECT = new SimpleModule()
            .addSerializer(Clock.class, new JsonSerializer<Clock>() {
                @Override
                public void serialize(Clock value, JsonGenerator gen, SerializerProvider serializers) throws IOException {
                    gen.writeStartObject();
                    gen.writeEndObject();
                }
            });

    // A plain mapper, the kind a Java service would use with no Kotlin support.
    private static final ObjectMapper PLAIN = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .registerModule(CLOCK_AS_EMPTY_OBJECT)
            .disable(SerializationFeature.FAIL_ON_EMPTY_BEANS);

    // The same mapper with the Kotlin module added, as DICE's own code uses it.
    private static final ObjectMapper WITH_KOTLIN = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .registerModule(new KotlinModule.Builder().build())
            .registerModule(CLOCK_AS_EMPTY_OBJECT)
            .disable(SerializationFeature.FAIL_ON_EMPTY_BEANS);

    @Test
    void dreamLoopReportWritesContextIdAsPlainKeyWithBothMappers() {
        DreamLoopReport report = ContextIdFixtures.dreamLoopReport("acme");

        for (ObjectMapper mapper : new ObjectMapper[]{PLAIN, WITH_KOTLIN}) {
            JsonNode tree = mapper.valueToTree(report);

            assertEquals("acme", tree.get("contextId").asText());
            assertNoMangledOrDuplicateKeys(tree);
        }
    }

    @Test
    void collectorRunContextWritesContextIdAsPlainKeyWithBothMappers() {
        CollectorRunContext context = ContextIdFixtures.collectorRunContext("acme");

        for (ObjectMapper mapper : new ObjectMapper[]{PLAIN, WITH_KOTLIN}) {
            JsonNode tree = mapper.valueToTree(context);

            assertEquals("acme", tree.get("contextId").asText());
            assertNoMangledOrDuplicateKeys(tree);
        }
    }

    /**
     * Fails if any key carries the compiler's hyphenated suffix or the String twin's name.
     *
     * @param tree The JSON object to check.
     */
    private static void assertNoMangledOrDuplicateKeys(JsonNode tree) {
        Iterator<String> names = tree.fieldNames();
        while (names.hasNext()) {
            String name = names.next();
            assertFalse(name.contains("-"), "Key carries a compiler suffix: " + name);
            assertFalse("contextIdValue".equals(name), "The String twin should not be written");
        }
    }
}
