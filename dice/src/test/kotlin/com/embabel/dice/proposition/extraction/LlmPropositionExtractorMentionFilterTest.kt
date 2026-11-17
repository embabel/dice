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
package com.embabel.dice.proposition.extraction

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.embabel.agent.api.common.Ai
import com.embabel.common.ai.model.LlmOptions
import com.embabel.dice.common.SourceAnalysisContext
import com.embabel.dice.common.SuggestedEntities
import com.embabel.dice.common.filter.GroundedMentionFilter
import com.embabel.dice.common.filter.MentionFilter
import com.embabel.dice.common.filter.MentionFixtures
import com.embabel.dice.common.validation.ValidationResult
import com.embabel.dice.proposition.SuggestedMention
import com.embabel.dice.proposition.SuggestedProposition
import com.embabel.dice.proposition.SuggestedPropositions
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.slf4j.LoggerFactory

/**
 * How [LlmPropositionExtractor.toSuggestedEntities] applies a mention filter handed to it directly.
 */
class LlmPropositionExtractorMentionFilterTest {

    private val extractor = LlmPropositionExtractor.withLlm(mock(LlmOptions::class.java)).withAi(mock(Ai::class.java))

    private val alice = MentionFixtures.mention("Alice")

    private val carol = MentionFixtures.mention("Carol")

    private val aliceAndCarol = MentionFixtures.proposition("Alice met Carol", alice, carol)

    /** Rejects the mention spanning "Carol" through [isValid] only. */
    private val rejectCarol = object : MentionFilter {
        override fun isValid(mention: SuggestedMention, propositionText: String): Boolean = mention.span != "Carol"
    }

    /** Rejects every mention whose span is not the name of a known entity, through [validateMentions]. */
    private val knownOnly = object : MentionFilter {
        override fun isValid(mention: SuggestedMention, propositionText: String): Boolean = true

        override fun validateMentions(
            proposition: SuggestedProposition,
            context: SourceAnalysisContext,
        ): List<ValidationResult> = proposition.mentions.map { mention ->
            if (context.knownEntities.any { it.name == mention.span }) {
                ValidationResult.Valid
            } else {
                ValidationResult.Invalid("unknown ${mention.span}")
            }
        }
    }

    private fun entities(
        vararg propositions: SuggestedProposition,
        context: SourceAnalysisContext = MentionFixtures.context(),
        filter: MentionFilter? = null,
    ): SuggestedEntities =
        extractor.toSuggestedEntities(SuggestedPropositions("chunk-1", propositions.toList()), context, null, filter)

    private fun names(result: SuggestedEntities): List<String> = result.suggestedEntities.map { it.name }

    @Test
    fun `E1 a filter that only implements isValid works as before`() {
        assertThat(names(entities(aliceAndCarol, filter = rejectCarol))).containsExactly("Alice")
    }

    @Test
    fun `E2 a filter that overrides validateMentions is honoured`() {
        val context = MentionFixtures.context(MentionFixtures.knownPerson("user-1", "Alice"))
        assertThat(names(entities(aliceAndCarol, context = context, filter = knownOnly))).containsExactly("Alice")
    }

    @Test
    fun `E3 the filter is asked once per proposition that has mentions with the given context`() {
        val seen = mutableListOf<SourceAnalysisContext>()
        val counting = object : MentionFilter {
            override fun isValid(mention: SuggestedMention, propositionText: String): Boolean = true

            override fun validateMentions(
                proposition: SuggestedProposition,
                context: SourceAnalysisContext,
            ): List<ValidationResult> {
                seen.add(context)
                return proposition.mentions.map { ValidationResult.Valid }
            }
        }
        val context = MentionFixtures.context()
        entities(
            aliceAndCarol,
            MentionFixtures.proposition("Nothing named here"),
            MentionFixtures.proposition("Bob left", MentionFixtures.mention("Bob")),
            context = context,
            filter = counting,
        )
        assertThat(seen).hasSize(2)
        seen.forEach { assertSame(context, it) }
    }

    @Test
    fun `E4 a filter that returns too few results throws and names its class`() {
        val short = object : MentionFilter {
            override fun isValid(mention: SuggestedMention, propositionText: String): Boolean = true

            override fun validateMentions(
                proposition: SuggestedProposition,
                context: SourceAnalysisContext,
            ): List<ValidationResult> = listOf(ValidationResult.Valid)
        }
        assertThatThrownBy { entities(aliceAndCarol, filter = short) }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining(short::class.java.name)
    }

    @Test
    fun `E5 a filtered mention is logged at debug level and nothing at info or above`() {
        val logger = LoggerFactory.getLogger(LlmPropositionExtractor::class.java) as Logger
        val previous = logger.level
        logger.level = Level.DEBUG
        val appender = ListAppender<ILoggingEvent>().also {
            it.start()
            logger.addAppender(it)
        }
        try {
            entities(aliceAndCarol, filter = rejectCarol)
        } finally {
            logger.detachAppender(appender)
            appender.stop()
            logger.level = previous
        }
        val filtered = appender.list.filter { it.formattedMessage.contains("Carol") }
        assertThat(filtered).hasSize(1)
        assertThat(filtered.single().level).isEqualTo(Level.DEBUG)
        assertThat(appender.list.filter { it.level.isGreaterOrEqual(Level.INFO) }).isEmpty()
    }

    @Test
    fun `E6 with no filter every mention becomes an entity and duplicates keep the suggested id`() {
        val plain = MentionFixtures.mention("Alice")
        val withId = MentionFixtures.mention("Alice", "id-1")
        val result = entities(
            MentionFixtures.proposition("Alice met Carol", plain, carol),
            MentionFixtures.proposition("Alice again", withId),
        )
        assertThat(names(result)).containsExactly("Alice", "Carol")
        assertThat(result.suggestedEntities.first().id).isEqualTo("id-1")
    }

    @Test
    fun `E7 the grounded filter agrees with the pipeline`() {
        val context = MentionFixtures.context(MentionFixtures.knownPerson("user-1", "Thomas", "Tom"))
        val proposition = MentionFixtures.proposition("Tom moved to Paris", MentionFixtures.mention("Thomas"))
        assertThat(names(entities(proposition, context = context, filter = GroundedMentionFilter())))
            .containsExactly("Thomas")
    }
}
