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

import com.embabel.agent.rag.model.NamedEntity;
import com.embabel.chat.Message;
import com.embabel.dice.common.SourceAnalysisRequestEvent;
import com.embabel.dice.incremental.IncrementalSource;
import com.embabel.dice.proposition.extraction.ExtractionPerspective;
import com.embabel.dice.proposition.extraction.IncrementalPropositionExtraction;
import com.embabel.dice.provenance.ContentAddressedLocator;
import com.embabel.dice.provenance.ProvenanceEntry;
import com.embabel.dice.provenance.SourceLocator;
import com.embabel.dice.provenance.SourceRevisionRef;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

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
    void retainsEveryLegacyRememberDescriptorAndAddsDistinctSourceDescriptors() throws Exception {
        Class<?>[][] legacyTextParameters = {
                {String.class, String.class, NamedEntity.class},
                {String.class, String.class, NamedEntity.class, List.class},
                {String.class, String.class, NamedEntity.class, List.class, ExtractionPerspective.class},
                {String.class, String.class, NamedEntity.class, List.class, ExtractionPerspective.class,
                        Boolean.class},
        };
        for (Class<?>[] parameters : legacyTextParameters) {
            IncrementalPropositionExtraction.class.getMethod("rememberText", parameters);
        }

        Class<?>[][] sourceAwareTextParameters = {
                {String.class, String.class, NamedEntity.class, SourceLocator.class},
                {String.class, String.class, NamedEntity.class, SourceLocator.class, SourceRevisionRef.class},
                {String.class, String.class, NamedEntity.class, SourceLocator.class, SourceRevisionRef.class,
                        List.class},
                {String.class, String.class, NamedEntity.class, SourceLocator.class, SourceRevisionRef.class,
                        List.class, ExtractionPerspective.class},
                {String.class, String.class, NamedEntity.class, SourceLocator.class, SourceRevisionRef.class,
                        List.class, ExtractionPerspective.class, Boolean.class},
        };
        for (Class<?>[] parameters : sourceAwareTextParameters) {
            IncrementalPropositionExtraction.class.getMethod("rememberTextFromSource", parameters);
        }
        assertThrows(
                NoSuchMethodException.class,
                () -> IncrementalPropositionExtraction.class.getMethod(
                        "rememberText",
                        String.class,
                        String.class,
                        NamedEntity.class,
                        SourceLocator.class
                )
        );

        IncrementalPropositionExtraction.class.getMethod(
                "rememberFile",
                InputStream.class,
                String.class,
                NamedEntity.class
        );
        IncrementalPropositionExtraction.class.getMethod(
                "rememberFileFromSource",
                InputStream.class,
                String.class,
                NamedEntity.class,
                SourceLocator.class
        );
        IncrementalPropositionExtraction.class.getMethod(
                "rememberFileFromSource",
                InputStream.class,
                String.class,
                NamedEntity.class,
                SourceLocator.class,
                SourceRevisionRef.class
        );
        assertThrows(
                NoSuchMethodException.class,
                () -> IncrementalPropositionExtraction.class.getMethod(
                        "rememberFile",
                        InputStream.class,
                        String.class,
                        NamedEntity.class,
                        SourceLocator.class
                )
        );
    }

    @Test
    void legacyAndRevisionAwareJavaEventSubclassesUseTheBaseConstructor() throws Exception {
        assertArrayEquals(
                new Class<?>[]{Object.class, NamedEntity.class},
                SourceAnalysisRequestEvent.class
                        .getDeclaredConstructor(Object.class, NamedEntity.class)
                        .getParameterTypes()
        );

        NamedEntity user = org.mockito.Mockito.mock(NamedEntity.class);
        LegacyJavaEvent legacy = new LegacyJavaEvent(this, user);
        assertSame(user, legacy.user);
        assertNull(legacy.sourceLocator());
        assertNull(legacy.sourceRevision());

        SourceLocator locator = new ContentAddressedLocator("java-event-source");
        SourceRevisionRef revision = new SourceRevisionRef(locator.key(), "opaque-r1");
        RevisionAwareJavaEvent revisionAware =
                new RevisionAwareJavaEvent(this, user, locator, revision);
        assertSame(locator, revisionAware.sourceLocator());
        assertSame(revision, revisionAware.sourceRevision());
        assertEquals("opaque-r1", revisionAware.sourceRevision().getSourceRevision());
    }

    /**
     * Compiling this body proves the legacy and additive Java source entry points remain callable.
     * It is intentionally never executed because extraction has observable side effects.
     */
    @SuppressWarnings({"unused", "DataFlowIssue"})
    private static void compileJavaSourceCalls(
            IncrementalPropositionExtraction extraction,
            InputStream input,
            NamedEntity user,
            SourceLocator locator,
            SourceRevisionRef revision
    ) {
        extraction.rememberFile(input, "legacy.txt", user);
        extraction.rememberFileFromSource(input, "source.txt", user, locator);
        extraction.rememberFileFromSource(input, "revisioned.txt", user, locator, revision);
        extraction.rememberText("legacy", "legacy-id", user);
        extraction.rememberText("legacy", "legacy-id", user, List.of());
        extraction.rememberText("legacy", "legacy-id", user, List.of(), null);
        extraction.rememberText("legacy", "legacy-id", user, List.of(), null, null);
        extraction.rememberTextFromSource("revisioned", "revisioned-id", user, locator);
        extraction.rememberTextFromSource("revisioned", "revisioned-id", user, locator, revision);
        extraction.rememberTextFromSource(
                "revisioned", "revisioned-id", user, locator, revision, List.of()
        );
        extraction.rememberTextFromSource(
                "revisioned", "revisioned-id", user, locator, revision, List.of(), null
        );
        extraction.rememberTextFromSource(
                "revisioned", "revisioned-id", user, locator, revision, List.of(), null, null
        );
    }

    private static final class LegacyJavaEvent extends SourceAnalysisRequestEvent {

        private LegacyJavaEvent(Object source, NamedEntity user) {
            super(source, user);
        }

        @Override
        public IncrementalSource<Message> incrementalSource() {
            throw new UnsupportedOperationException("Not needed by this compatibility test");
        }
    }

    private static final class RevisionAwareJavaEvent extends SourceAnalysisRequestEvent {

        private final SourceLocator locator;
        private final SourceRevisionRef revision;

        private RevisionAwareJavaEvent(
                Object source,
                NamedEntity user,
                SourceLocator locator,
                SourceRevisionRef revision
        ) {
            super(source, user);
            this.locator = locator;
            this.revision = revision;
        }

        @Override
        public IncrementalSource<Message> incrementalSource() {
            throw new UnsupportedOperationException("Not needed by this compatibility test");
        }

        @Override
        public SourceLocator sourceLocator() {
            return locator;
        }

        @Override
        public SourceRevisionRef sourceRevision() {
            return revision;
        }
    }
}
