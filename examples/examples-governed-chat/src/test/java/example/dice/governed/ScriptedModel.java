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
package example.dice.governed;

import com.embabel.agent.spi.LlmService;
import example.dice.common.ScriptedChatModel;
import example.dice.common.ScriptedLlm;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.core.io.ClassPathResource;

@TestConfiguration(proxyBeanMethods = false)
class ScriptedModel {

    @Bean
    LlmService<?> scriptedLlm() {
        var model = ScriptedChatModel.script()
                .whenPromptContains("Only supervisors may approve refunds.", reply("policy"))
                .whenPromptContains("Support agents may approve refunds.", reply("dispute"))
                .whenPromptContains("Maybe refunds must be mailed.", reply("hearsay"))
                .whenPromptContains("The customer prefers refund updates by email.", reply("preference"))
                .build();
        return ScriptedLlm.service(model);
    }

    private static ClassPathResource reply(String name) {
        return new ClassPathResource("recorded/" + name + ".json");
    }
}
