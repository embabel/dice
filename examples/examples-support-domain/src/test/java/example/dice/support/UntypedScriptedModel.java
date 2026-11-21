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

import com.embabel.agent.core.DataDictionary;
import com.embabel.agent.spi.LlmService;
import example.dice.common.ScriptedChatModel;
import example.dice.common.ScriptedLlm;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.core.io.ClassPathResource;

/**
 * The setup for running chat 1 with a schema that names no types. The support schema is
 * swapped for an empty one, and the model replays what it said for chat 1 when it was shown
 * no Customer, Contact or Plan.
 */
@TestConfiguration(proxyBeanMethods = false)
class UntypedScriptedModel {

    /**
     * Takes the place of the support schema, and lists no types.
     *
     * <p>The desk and the entity store ask for {@code supportDictionary} by qualifier. This bean
     * carries that qualifier too, and being primary it wins over the real schema. Giving it the
     * same bean name instead does not work: the real schema is registered after the test's
     * configuration and replaces it.
     *
     * @return a schema with no types in it
     */
    @Bean
    @Primary
    @Qualifier("supportDictionary")
    DataDictionary untypedSupportDictionary() {
        return DataDictionary.fromClasses("support");
    }

    /**
     * Replays the untyped reply for chat 1, matched on words only that chat's text has.
     *
     * @return the scripted model
     */
    @Bean
    ScriptedChatModel scriptedChatModel() {
        return ScriptedChatModel.script()
                .whenPromptContains("ten more seats", new ClassPathResource("recorded/chat-1-untyped.json"))
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
