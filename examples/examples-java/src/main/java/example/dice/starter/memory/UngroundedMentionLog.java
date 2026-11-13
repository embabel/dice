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
package example.dice.starter.memory;

import com.embabel.dice.common.UngroundedMentionsFound;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Logs the mentions the model attached to a sentence that does not contain them.
 */
@Component
class UngroundedMentionLog {

    private static final Logger logger = LoggerFactory.getLogger(UngroundedMentionLog.class);

    /**
     * Logs one line per ticket that had ungrounded mentions.
     *
     * @param found the mentions, the account and ticket they came from, and what the pipeline
     *              did with them
     */
    @EventListener
    void on(UngroundedMentionsFound found) {
        logger.warn("account {}, {}: {} mention(s) missing from their own sentence, handling={}",
                found.getContextIdValue(), found.getChunkId(), found.getMentions().size(), found.getHandling());
    }
}
