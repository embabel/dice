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
package com.embabel.dice.pipeline

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.embabel.agent.rag.model.Chunk
import com.embabel.dice.common.filter.MentionFilter
import com.embabel.dice.common.filter.MentionFixtures
import com.embabel.dice.proposition.SuggestedMention
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory

/**
 * Checks what the pipeline logs for rejected mentions: one line of counts per chunk, with spans
 * and reasons at debug level only.
 */
class PropositionPipelineRejectionLoggingTest {

    private val rejectCarol = object : MentionFilter {
        override fun isValid(mention: SuggestedMention, propositionText: String): Boolean = mention.span != "Carol"
        override fun rejectionReason(mention: SuggestedMention): String? = "no Carols"
    }

    private val chunk = Chunk(id = "chunk-1", text = "any", metadata = emptyMap(), parentId = "")

    private fun mixed() = MentionFixtures.proposition("Alice met Bob", MentionFixtures.mention("Alice"), MentionFixtures.mention("Carol"))

    private fun aliceMetCarol() =
        MentionFixtures.proposition("Alice met Carol", MentionFixtures.mention("Alice"), MentionFixtures.mention("Carol"))

    private fun base(vararg propositions: com.embabel.dice.proposition.SuggestedProposition) =
        PropositionPipeline.withExtractor(SuggestingPropositionExtractor.of(*propositions))

    /**
     * Runs the block with a list appender on the pipeline logger set to the given level.
     *
     * @param level the level of the pipeline logger during the block
     * @param block the call to observe
     * @return the log events, in order
     */
    private fun logged(level: Level = Level.DEBUG, block: () -> Unit): List<ILoggingEvent> {
        val logger = LoggerFactory.getLogger(PropositionPipeline::class.java) as Logger
        val previous = logger.level
        val appender = ListAppender<ILoggingEvent>().also {
            it.start()
            logger.addAppender(it)
        }
        logger.level = level
        try {
            block()
        } finally {
            logger.level = previous
            logger.detachAppender(appender)
            appender.stop()
        }
        return appender.list.toList()
    }

    private fun List<ILoggingEvent>.atInfoOrAbove() = filter { it.level.isGreaterOrEqual(Level.INFO) }

    @Test
    fun `E1 drop grounding logs one warning with counts and no span`() {
        val events = logged {
            base(mixed()).withUngroundedMentions(UngroundedMentionHandling.DROP)
                .processChunk(chunk, MentionFixtures.context())
        }
        val loud = events.atInfoOrAbove()
        assertEquals(1, loud.size)
        assertEquals(Level.WARN, loud.single().level)
        assertEquals(
            "Chunk chunk-1: 1 mention(s) rejected (grounding: 1 rejected, 1 dropped; filter: 0 rejected, 0 left unresolved, 0 dropped)",
            loud.single().formattedMessage,
        )
        assertTrue(loud.none { "Carol" in it.formattedMessage })
        assertTrue(events.filter { it.level == Level.DEBUG }.any { "Carol" in it.formattedMessage })
    }

    @Test
    fun `E2 the default pipeline logs the count line at debug only`() {
        val events = logged {
            base(mixed()).processChunk(chunk, MentionFixtures.context())
        }
        assertTrue(events.atInfoOrAbove().isEmpty())
        assertTrue(events.any { it.level == Level.DEBUG && "mention(s) rejected (grounding" in it.formattedMessage })
    }

    @Test
    fun `E3 a filter rejection left unresolved logs a warning without the reason`() {
        val events = logged {
            base(aliceMetCarol()).withMentionFilter(rejectCarol).processChunk(chunk, MentionFixtures.context())
        }
        val loud = events.atInfoOrAbove()
        assertEquals(1, loud.size)
        assertEquals(Level.WARN, loud.single().level)
        assertTrue(
            loud.single().formattedMessage
                .endsWith("(grounding: 0 rejected, 0 dropped; filter: 1 rejected, 1 left unresolved, 0 dropped)"),
        )
        assertTrue(loud.none { "no Carols" in it.formattedMessage })
    }

    @Test
    fun `E4 a filter rejection that is kept logs nothing at info or above`() {
        val events = logged {
            base(aliceMetCarol()).withMentionFilter(rejectCarol, RejectedMentionHandling.KEEP)
                .processChunk(chunk, MentionFixtures.context())
        }
        assertTrue(events.atInfoOrAbove().isEmpty())
    }

    @Test
    fun `E5 a chunk with no rejected mention logs no rejection line`() {
        val events = logged {
            base(MentionFixtures.proposition("Alice met Bob", MentionFixtures.mention("Alice"), MentionFixtures.mention("Bob")))
                .withUngroundedMentions(UngroundedMentionHandling.DROP)
                .processChunk(chunk, MentionFixtures.context())
        }
        assertFalse(events.any { "rejected" in it.formattedMessage })
    }

    @Test
    fun `E6 with the logger at warn the count line is the only event`() {
        val events = logged(Level.WARN) {
            base(mixed()).withUngroundedMentions(UngroundedMentionHandling.DROP)
                .processChunk(chunk, MentionFixtures.context())
        }
        assertEquals(1, events.size)
        assertTrue("mention(s) rejected (grounding" in events.single().formattedMessage)
    }

    @Test
    fun `E7 a reason that quotes the span stays at debug level`() {
        val rejectAll = object : MentionFilter {
            override fun isValid(mention: SuggestedMention, propositionText: String): Boolean = false
            override fun rejectionReason(mention: SuggestedMention): String? = "Carol is not here"
        }
        val events = logged {
            base(mixed()).withMentionGrounding(rejectAll).withUngroundedMentions(UngroundedMentionHandling.DROP)
                .processChunk(chunk, MentionFixtures.context())
        }
        val loud = events.atInfoOrAbove()
        assertTrue(loud.none { "Carol" in it.formattedMessage || "Alice" in it.formattedMessage })
        assertTrue(events.any { it.level == Level.DEBUG && "Carol is not here" in it.formattedMessage })
    }
}
