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
package example.dice.support;

import com.embabel.agent.spi.LlmService;
import example.dice.common.ScriptedChatModel;
import example.dice.common.ScriptedLlm;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.core.io.ClassPathResource;

/**
 * The model the tests run against. It replays a recorded reply for each chat, so no real
 * model is called and no API key is needed.
 */
@TestConfiguration(proxyBeanMethods = false)
class ScriptedModel {

    /**
     * Replays one recorded reply per chat, matched on words only that chat's text has. The
     * extraction prompt contains the chat text, so the words pick the reply.
     *
     * @return the scripted model
     */
    @Bean
    ScriptedChatModel scriptedChatModel() {
        return ScriptedChatModel.script()
                .whenPromptContains("ten more seats", new ClassPathResource("recorded/chat-1-pro.json"))
                .whenPromptContains("this is Dana", new ClassPathResource("recorded/chat-2-basic.json"))
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
}
