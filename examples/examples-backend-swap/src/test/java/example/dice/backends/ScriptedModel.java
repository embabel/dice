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
package example.dice.backends;

import com.embabel.agent.spi.LlmService;
import example.dice.common.ScriptedChatModel;
import example.dice.common.ScriptedLlm;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

/**
 * A placeholder model for the tests. Embabel Agent will not start without a default model,
 * and both DICE stores are built from the agent platform, so one has to be registered. Nothing
 * in this example calls a model, so the script has no rules.
 */
@TestConfiguration(proxyBeanMethods = false)
class ScriptedModel {

    /**
     * Registers an empty scripted model with Embabel Agent under the name the tests set as the
     * default model.
     *
     * @return the LLM service Embabel Agent picks up
     */
    @Bean
    LlmService<?> scriptedLlm() {
        return ScriptedLlm.service(ScriptedChatModel.script().build());
    }
}
