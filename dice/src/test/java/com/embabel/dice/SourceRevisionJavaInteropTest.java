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

import com.embabel.dice.provenance.ContentAddressedLocator;
import com.embabel.dice.provenance.ProvenanceEntry;
import com.embabel.dice.provenance.SourceLocator;
import com.embabel.dice.provenance.SourceRevisionRef;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Java's view of the revision contract. {@code @JvmOverloads} on {@link ProvenanceEntry} means
 * every constructor descriptor a Java caller could already have compiled against survives, and the
 * new revision argument arrives as one extra descriptor on the end.
 */
class SourceRevisionJavaInteropTest {

    @Test
    void retainsEveryLegacyJavaConcreteConstructorDescriptor() throws Exception {
        Class<?>[][] provenanceParameters = {
                {SourceLocator.class},
                {SourceLocator.class, String.class},
                {SourceLocator.class, String.class, Integer.class},
                {SourceLocator.class, String.class, Integer.class, Integer.class},
                {SourceLocator.class, String.class, Integer.class, Integer.class, String.class},
        };
        for (Class<?>[] parameters : provenanceParameters) {
            ProvenanceEntry.class.getConstructor(parameters);
        }
        ProvenanceEntry.class.getConstructor(
                SourceLocator.class,
                String.class,
                Integer.class,
                Integer.class,
                String.class,
                String.class
        );
    }

    @Test
    void javaCallersReadAndBuildRevisionValues() {
        SourceLocator locator = new ContentAddressedLocator("java-source");
        SourceRevisionRef revision = new SourceRevisionRef(locator.key(), "opaque-r1");
        assertEquals(locator.key(), revision.getSourceKey());
        assertEquals("opaque-r1", revision.getSourceRevision());

        ProvenanceEntry legacy = new ProvenanceEntry(locator);
        assertNull(legacy.getSourceRevision());

        ProvenanceEntry revisioned = new ProvenanceEntry(
                locator,
                "chunk",
                0,
                4,
                "hash",
                revision.getSourceRevision()
        );
        assertEquals("opaque-r1", revisioned.getSourceRevision());
        assertEquals("hash", revisioned.getContentHash());
    }
}
