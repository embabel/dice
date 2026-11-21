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
package example.dice.mcp;

import com.embabel.agent.spi.LlmService;
import example.dice.common.ScriptedChatModel;
import example.dice.common.ScriptedLlm;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Gives the agent platform a model so it will start without an API key.
 *
 * <p>Embabel Agent refuses to start when it finds no model at all. DICE's MCP tools never call
 * a model, so this app registers a placeholder: a scripted model with an empty script. It
 * answers nothing, and any prompt sent to it fails.
 *
 * <p>To use a real model, add an Embabel Agent provider starter, such as the OpenAI or
 * Anthropic one, and point {@code embabel.models.default-llm} at one of its models.
 */
@Configuration(proxyBeanMethods = false)
class NoModelConfiguration {

    /**
     * Registers the placeholder model under the name {@code application.properties} sets as the
     * default.
     *
     * @return the LLM service Embabel Agent picks up
     */
    @Bean
    LlmService<?> placeholderLlm() {
        return ScriptedLlm.service(ScriptedChatModel.script().build());
    }
}
