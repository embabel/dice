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
import com.embabel.dice.proposition.PropositionStatus;
import com.embabel.dice.proposition.extraction.ExtractionPerspective;
import com.embabel.dice.proposition.extraction.IncrementalPropositionExtraction;
import com.embabel.dice.provenance.ContentAddressedLocator;
import com.embabel.dice.provenance.ProvenanceEntry;
import com.embabel.dice.provenance.SourceLocator;
import com.embabel.dice.provenance.SourceRevisionRef;
import com.embabel.dice.query.discovery.LineageDto;
import com.embabel.dice.spi.RetiredProposition;
import com.embabel.dice.web.rest.ExtractOptions;
import com.embabel.dice.web.rest.ExtractRequest;
import com.embabel.dice.web.rest.ProvenanceEntryDto;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Java's view of the revision contract. {@code @JvmOverloads} on {@link ProvenanceEntry},
 * {@link RetiredProposition}, {@link ExtractRequest}, {@link ProvenanceEntryDto} and
 * {@link LineageDto} means every constructor descriptor a Java caller could already have
 * compiled against survives, and each new argument arrives as one extra descriptor on the end.
 * The remember entry points work the other way round: the revision-aware calls are separate
 * methods, so the old {@code rememberText} and {@code rememberFile} descriptors never move.
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

        ExtractRequest.class.getConstructor(
                String.class,
                String.class,
                List.class,
                String.class,
                ExtractOptions.class
        );
        ProvenanceEntryDto.class.getConstructor(
                String.class,
                String.class,
                String.class,
                Integer.class,
                Integer.class,
                String.class
        );
        LineageDto.class.getConstructor(
                String.class,
                String.class,
                String.class,
                int.class,
                List.class,
                List.class
        );

        RetiredProposition.class.getConstructor(
                String.class,
                PropositionStatus.class,
                List.class,
                List.class,
                List.class
        );
        RetiredProposition.class.getConstructor(
                String.class,
                PropositionStatus.class,
                List.class,
                List.class,
                List.class,
                List.class
        );
    }

    @Test
    void javaCallersBuildAndReadFoldedEvidenceKeys() {
        RetiredProposition legacy = new RetiredProposition(
                "retired",
                PropositionStatus.ACTIVE,
                List.of("chunk-1"),
                List.of("uri:https://example.com/source"),
                List.of("src-1")
        );
        assertTrue(legacy.getFoldedProvenanceEvidenceKeys().isEmpty());

        RetiredProposition revisioned = new RetiredProposition(
                "retired",
                PropositionStatus.ACTIVE,
                List.of("chunk-1"),
                List.of("uri:https://example.com/source"),
                List.of("src-1"),
                List.of("dice-provenance:v1:opaque")
        );
        assertEquals(List.of("dice-provenance:v1:opaque"), revisioned.getFoldedProvenanceEvidenceKeys());
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
