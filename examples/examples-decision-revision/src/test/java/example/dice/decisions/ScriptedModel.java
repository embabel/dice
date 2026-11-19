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
package example.dice.decisions;

import com.embabel.agent.spi.LlmService;
import example.dice.common.ScriptedChatModel;
import example.dice.common.ScriptedLlm;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.core.io.ClassPathResource;

/**
 * The model the tests run against. It answers both the extraction prompts and the revision
 * decisions from recorded replies, so no real model is called.
 */
@TestConfiguration(proxyBeanMethods = false)
class ScriptedModel {

    /**
     * Replays one recorded reply per message and one per revision decision.
     *
     * <p>A decision prompt's user message starts with {@code {"input":"New proposition: } and
     * the new fact, and it also lists earlier facts as candidates. Those candidate sentences
     * are what the extraction rules match on, so the decision rules come first.
     *
     * @return the scripted model
     */
    @Bean
    ScriptedChatModel scriptedChatModel() {
        return ScriptedChatModel.script()
                .whenPromptContains(decisionAbout("Acme uses the Team plan"),
                        new ClassPathResource("recorded/decide-2-duplicate.json"))
                .whenPromptContains(decisionAbout("Acme renewed the Team plan for a year"),
                        new ClassPathResource("recorded/decide-3-reinforces.json"))
                .whenPromptContains(decisionAbout("Acme moved to the Enterprise plan"),
                        new ClassPathResource("recorded/decide-4-contradicts.json"))
                .whenPromptContains(decisionAbout("Acme prefers email over phone"),
                        new ClassPathResource("recorded/decide-5-unrelated.json"))
                .whenPromptContains(decisionAbout("Acme has 40 seats on the Enterprise plan"),
                        new ClassPathResource("recorded/decide-6-garbled.txt"))
                .whenPromptContains("Acme is on the Team plan", new ClassPathResource("recorded/extract-1-team.json"))
                .whenPromptContains("Acme uses the Team plan", new ClassPathResource("recorded/extract-2-uses-team.json"))
                .whenPromptContains("Acme renewed the Team plan for a year",
                        new ClassPathResource("recorded/extract-3-renewed.json"))
                .whenPromptContains("Acme moved to the Enterprise plan",
                        new ClassPathResource("recorded/extract-4-enterprise.json"))
                .whenPromptContains("Acme prefers email over phone", new ClassPathResource("recorded/extract-5-email.json"))
                .whenPromptContains("Acme has 40 seats on the Enterprise plan",
                        new ClassPathResource("recorded/extract-6-seats.json"))
                .build();
    }

    /**
     * The start of the user message of a revision decision about a new fact.
     *
     * @param sentence the new fact
     * @return text that only that decision's prompt contains
     */
    static String decisionAbout(String sentence) {
        return "{\"input\":\"New proposition: " + sentence;
    }

    /**
     * Registers the scripted model with Embabel Agent under the name the test properties use
     * for the default model and the revision decision role.
     *
     * @param scriptedChatModel the model to register
     * @return the LLM service Embabel Agent picks up
     */
    @Bean
    LlmService<?> scriptedLlm(ScriptedChatModel scriptedChatModel) {
        return ScriptedLlm.service(scriptedChatModel);
    }
}
