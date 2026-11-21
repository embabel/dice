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
package example.dice.corrections;

import com.embabel.agent.spi.LlmService;
import example.dice.common.ScriptedChatModel;
import example.dice.common.ScriptedLlm;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.core.io.ClassPathResource;

/**
 * The model the tests run against. It answers every extraction and classification call in
 * the story from a recorded reply, so no real model is called.
 */
@TestConfiguration(proxyBeanMethods = false)
class ScriptedModel {

    /**
     * Replays one recorded reply per model call.
     *
     * <p>A classification prompt shows the new fact as {@code Text: "<sentence>"}, and it also
     * lists the stored facts it is compared with, whose sentences are the extraction keys of
     * earlier turns. So the classification rules come first, latest turn first, and only then
     * the extraction rules, which match on a few words of the message.
     *
     * <p>Turns 1, 2 and 5 never reach classification: turn 1 and turn 5 find no similar stored
     * fact, and turn 2 repeats turn 1 word for word.
     *
     * @return the scripted model
     */
    @Bean
    ScriptedChatModel scriptedChatModel() {
        return ScriptedChatModel.script()
                .whenPromptContains("Text: \"Acme moved to the Basic plan\"", recording("classify-7-contradictory"))
                .whenPromptContains("Text: \"Acme's invoices are paid monthly\"", recording("classify-6-contradictory"))
                .whenPromptContains("Text: \"Acme is a paying customer\"", recording("classify-4-generalizes"))
                .whenPromptContains("Text: \"Acme pays for the Pro plan\"", recording("classify-3-similar"))
                .whenPromptContains("Acme is on the Pro plan", recording("extract-1-pro"))
                .whenPromptContains("pays for the Pro plan", recording("extract-3-pays-pro"))
                .whenPromptContains("a paying customer", recording("extract-4-paying"))
                .whenPromptContains("paid annually", recording("extract-5-annual"))
                .whenPromptContains("paid monthly", recording("extract-6-monthly"))
                .whenPromptContains("moved to the Basic plan", recording("extract-7-basic"))
                .build();
    }

    /**
     * Registers the scripted model with Embabel Agent under the name the test properties set
     * as the default model.
     *
     * @param scriptedChatModel the model to register
     * @return the LLM service Embabel Agent picks up
     */
    @Bean
    LlmService<?> scriptedLlm(ScriptedChatModel scriptedChatModel) {
        return ScriptedLlm.service(scriptedChatModel);
    }

    /**
     * @param name a recording's file name without the extension
     * @return the recording under {@code src/test/resources/recorded/}
     */
    private static ClassPathResource recording(String name) {
        return new ClassPathResource("recorded/" + name + ".json");
    }
}
