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

import com.embabel.agent.core.DataDictionary;
import com.embabel.agent.rag.model.NamedEntity;
import com.embabel.chat.Conversation;
import com.embabel.chat.Message;
import com.embabel.dice.common.ConversationAnalysisRequestEvent;
import com.embabel.dice.common.SourceAnalysisContext;
import com.embabel.dice.common.SourceAnalysisRequestEvent;
import com.embabel.dice.common.resolver.AlwaysCreateEntityResolver;
import com.embabel.dice.incremental.IncrementalSource;
import com.embabel.dice.proposition.extraction.ExtractionContentProfileRef;
import com.embabel.dice.proposition.extraction.ExtractionPerspective;
import com.embabel.dice.proposition.extraction.ExtractionRequest;
import com.embabel.dice.proposition.extraction.IncrementalPropositionExtraction;
import com.embabel.dice.provenance.ContentAddressedLocator;
import com.embabel.dice.provenance.SourceLocator;
import com.embabel.dice.provenance.SourceRevisionRef;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.lang.reflect.Modifier;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Java's view of the profile contract, with and without a profile present. A profile travels on an
 * {@link ExtractionRequest}, so the remember entry points keep every descriptor a Java caller could
 * already have compiled against and each name gains exactly one more, taking the request.
 */
class ExtractionProfileJavaInteropTest {

    private SourceAnalysisContext context() {
        return SourceAnalysisContext
                .withContextId("java-profile")
                .withEntityResolver(AlwaysCreateEntityResolver.INSTANCE)
                .withSchema(DataDictionary.fromClasses("java-profile"));
    }

    @Test
    void javaCallersBuildAndReadProfileValues() {
        ExtractionContentProfileRef profile = new ExtractionContentProfileRef("house-style", "v1");
        assertEquals("house-style", profile.getName());
        assertEquals("v1", profile.getVersion());
        assertEquals(profile, new ExtractionContentProfileRef("house-style", "v1"));
        assertNotEquals(profile, new ExtractionContentProfileRef("house-style", "v2"));
        assertThrows(
                IllegalArgumentException.class,
                () -> new ExtractionContentProfileRef(" ", "v1")
        );
    }

    @Test
    void javaBuiltContextsCarryNoProfileUnlessAsked() {
        SourceAnalysisContext absent = context();
        assertNull(absent.getProfile());

        ExtractionContentProfileRef profile = new ExtractionContentProfileRef("house-style", "v1");
        SourceAnalysisContext present = absent.withProfile(profile);

        assertSame(profile, present.getProfile());
        // The copy is a copy: the original is untouched.
        assertNull(absent.getProfile());
    }

    @Test
    void retainsEveryLegacyRememberDescriptorAndAddsRequestAwareOnes() throws Exception {
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
        IncrementalPropositionExtraction.class.getMethod(
                "rememberText",
                String.class, String.class, NamedEntity.class, List.class,
                ExtractionPerspective.class, Boolean.class, ExtractionRequest.class
        );

        IncrementalPropositionExtraction.class.getMethod(
                "rememberFile", InputStream.class, String.class, NamedEntity.class
        );
        IncrementalPropositionExtraction.class.getMethod(
                "rememberFile",
                InputStream.class, String.class, NamedEntity.class, ExtractionRequest.class
        );

        // A profile reaches extraction on the request, so no entry point takes one directly and
        // none lands where a caller filling every legacy argument already is.
        assertThrows(
                NoSuchMethodException.class,
                () -> IncrementalPropositionExtraction.class.getMethod(
                        "rememberText",
                        String.class, String.class, NamedEntity.class,
                        ExtractionContentProfileRef.class
                )
        );
        assertThrows(
                NoSuchMethodException.class,
                () -> IncrementalPropositionExtraction.class.getMethod(
                        "rememberText",
                        String.class, String.class, NamedEntity.class, List.class,
                        ExtractionPerspective.class, Boolean.class, ExtractionContentProfileRef.class
                )
        );
        assertThrows(
                NoSuchMethodException.class,
                () -> IncrementalPropositionExtraction.class.getMethod(
                        "rememberFile",
                        InputStream.class, String.class, NamedEntity.class,
                        ExtractionContentProfileRef.class
                )
        );
    }

    @Test
    void javaCallersBuildAndReadProfileBearingRequests() throws Exception {
        ExtractionContentProfileRef profile = new ExtractionContentProfileRef("house-style", "v1");
        SourceLocator locator = new ContentAddressedLocator("java-profile-source");
        SourceRevisionRef revision = new SourceRevisionRef(locator.key(), "r1");

        ExtractionRequest.class.getConstructor(
                SourceLocator.class, SourceRevisionRef.class, ExtractionContentProfileRef.class
        );

        // A profile needs no source of its own: the two dimensions stay independent on a request.
        ExtractionRequest profileOnly = new ExtractionRequest(null, null, profile);
        assertSame(profile, profileOnly.getProfile());
        assertNull(profileOnly.getSourceLocator());

        ExtractionRequest everything = new ExtractionRequest(locator, revision, profile);
        assertSame(profile, everything.getProfile());
        assertSame(revision, everything.getSourceRevision());

        // The copy helper is a copy: the request it was called on is untouched.
        assertSame(profile, ExtractionRequest.NONE.withProfile(profile).getProfile());
        assertNull(ExtractionRequest.NONE.getProfile());
    }

    @Test
    void legacyAndProfileAwareJavaEventSubclassesUseTheBaseConstructor() {
        NamedEntity user = org.mockito.Mockito.mock(NamedEntity.class);

        LegacyJavaEvent legacy = new LegacyJavaEvent(this, user);
        assertNull(legacy.profile());

        ExtractionContentProfileRef profile = new ExtractionContentProfileRef("house-style", "v1");
        ProfileAwareJavaEvent profileAware = new ProfileAwareJavaEvent(this, user, profile);
        assertSame(profile, profileAware.profile());
        // A subclass that only knows about profiles still carries no source provenance.
        assertNull(profileAware.sourceLocator());
        assertNull(profileAware.sourceRevision());
    }

    @Test
    void conversationEventKeepsItsFiveArgumentConstructorAndGainsProfileAwareOnes() throws Exception {
        ConversationAnalysisRequestEvent.class.getConstructor(
                Object.class, NamedEntity.class, Conversation.class
        );
        // The 4-argument form (locator only, no revision) is one `@JvmOverloads` emits from the
        // longer constructor's first defaulted parameter — a Java caller that compiled against it
        // must keep compiling.
        ConversationAnalysisRequestEvent.class.getConstructor(
                Object.class, NamedEntity.class, Conversation.class, SourceLocator.class
        );
        ConversationAnalysisRequestEvent.class.getConstructor(
                Object.class, NamedEntity.class, Conversation.class, SourceLocator.class,
                SourceRevisionRef.class
        );
        ConversationAnalysisRequestEvent.class.getConstructor(
                Object.class, NamedEntity.class, Conversation.class, SourceLocator.class,
                SourceRevisionRef.class, ExtractionContentProfileRef.class
        );

        NamedEntity user = org.mockito.Mockito.mock(NamedEntity.class);
        Conversation conversation = org.mockito.Mockito.mock(Conversation.class);
        SourceLocator locator = new ContentAddressedLocator("java-conversation");
        SourceRevisionRef revision = new SourceRevisionRef(locator.key(), "r1");
        ExtractionContentProfileRef profile = new ExtractionContentProfileRef("house-style", "v1");

        ConversationAnalysisRequestEvent locatorOnly =
                new ConversationAnalysisRequestEvent(this, user, conversation, locator);
        assertSame(locator, locatorOnly.sourceLocator());
        assertNull(locatorOnly.sourceRevision());
        assertNull(locatorOnly.profile());

        ConversationAnalysisRequestEvent legacy =
                new ConversationAnalysisRequestEvent(this, user, conversation, locator, revision);
        assertSame(locator, legacy.sourceLocator());
        assertSame(revision, legacy.sourceRevision());
        assertNull(legacy.profile());

        ConversationAnalysisRequestEvent profiled = new ConversationAnalysisRequestEvent(
                this, user, conversation, locator, revision, profile
        );
        assertSame(profile, profiled.profile());

        // A profile without a typed source: the locator argument is nullable because the two
        // dimensions are independent.
        ConversationAnalysisRequestEvent profileOnly = new ConversationAnalysisRequestEvent(
                this, user, conversation, null, null, profile
        );
        assertNull(profileOnly.sourceLocator());
        assertSame(profile, profileOnly.profile());
    }

    @Test
    void javaSubclassesStillOverrideTheSignaturesThatPredateRequests() throws Exception {
        // The proof is that this file compiles: javac rejects @Override on a final method, so
        // LegacyOverridingJavaExtraction would not build if adding the request argument had
        // turned the six-argument rememberText or the three-argument rememberFile into the
        // final bridges @JvmOverloads emits for reduced arities.
        assertFalse(
                Modifier.isFinal(
                        IncrementalPropositionExtraction.class.getMethod(
                                "rememberText",
                                String.class, String.class, NamedEntity.class, List.class,
                                ExtractionPerspective.class, Boolean.class
                        ).getModifiers()
                )
        );
        assertFalse(
                Modifier.isFinal(
                        IncrementalPropositionExtraction.class.getMethod(
                                "rememberFile",
                                InputStream.class, String.class, NamedEntity.class
                        ).getModifiers()
                )
        );
        LegacyOverridingJavaExtraction.class.getDeclaredMethod(
                "rememberText",
                String.class, String.class, NamedEntity.class, List.class,
                ExtractionPerspective.class, Boolean.class
        );
        LegacyOverridingJavaExtraction.class.getDeclaredMethod(
                "rememberFile", InputStream.class, String.class, NamedEntity.class
        );
    }

    /**
     * A Java subclass written before requests existed, overriding the entry-point signatures
     * that were open then. It is never instantiated; compiling it is the assertion.
     */
    @SuppressWarnings("unused")
    private static final class LegacyOverridingJavaExtraction extends IncrementalPropositionExtraction {

        private LegacyOverridingJavaExtraction(
                com.embabel.dice.pipeline.PropositionPipeline propositionPipeline,
                com.embabel.dice.incremental.ChunkHistoryStore chunkHistoryStore,
                DataDictionary dataDictionary,
                com.embabel.dice.common.Relations relations,
                com.embabel.dice.proposition.PropositionRepository propositionRepository,
                com.embabel.agent.rag.service.NamedEntityDataRepository entityRepository,
                com.embabel.dice.common.EntityResolver entityResolver,
                com.embabel.dice.projection.graph.GraphProjectionService graphProjectionService,
                com.embabel.dice.proposition.extraction.PropositionExtractionProperties properties
        ) {
            super(
                    propositionPipeline, chunkHistoryStore, dataDictionary, relations,
                    propositionRepository, entityRepository, entityResolver,
                    graphProjectionService, properties
            );
        }

        @Override
        public void rememberText(
                String text,
                String sourceId,
                NamedEntity user,
                List<String> additionalGrounding,
                ExtractionPerspective perspective,
                Boolean mintNewEntities
        ) {
            // A host's interception point; deliberately does nothing.
        }

        @Override
        public void rememberFile(InputStream inputStream, String filename, NamedEntity user) {
            // A host's interception point; deliberately does nothing.
        }
    }

    /**
     * Compiling this body proves the legacy and additive Java source entry points remain
     * callable. It is intentionally never executed because extraction has observable side
     * effects.
     */
    @SuppressWarnings({"unused", "DataFlowIssue"})
    private static void compileJavaSourceCalls(
            IncrementalPropositionExtraction extraction,
            InputStream input,
            NamedEntity user,
            SourceLocator locator,
            SourceRevisionRef revision,
            ExtractionContentProfileRef profile
    ) {
        extraction.rememberText("legacy", "legacy-id", user);
        extraction.rememberText("legacy", "legacy-id", user, List.of(), null, null);
        extraction.rememberText(
                "profiled", "profiled-id", user, List.of(), null, null,
                new ExtractionRequest(null, null, profile)
        );
        extraction.rememberFile(input, "legacy.txt", user);
        extraction.rememberFile(input, "profiled.txt", user, new ExtractionRequest(null, null, profile));
        extraction.rememberText(
                "source", "source-id", user, List.of(), null, null,
                new ExtractionRequest(locator, revision, profile)
        );
        extraction.rememberFile(
                input, "source.txt", user, new ExtractionRequest(locator, revision, profile)
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

    private static final class ProfileAwareJavaEvent extends SourceAnalysisRequestEvent {

        private final ExtractionContentProfileRef profile;

        private ProfileAwareJavaEvent(
                Object source,
                NamedEntity user,
                ExtractionContentProfileRef profile
        ) {
            super(source, user);
            this.profile = profile;
        }

        @Override
        public IncrementalSource<Message> incrementalSource() {
            throw new UnsupportedOperationException("Not needed by this compatibility test");
        }

        @Override
        public ExtractionContentProfileRef profile() {
            return profile;
        }
    }
}
