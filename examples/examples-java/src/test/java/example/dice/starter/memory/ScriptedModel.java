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

import com.embabel.agent.spi.LlmService;
import com.embabel.dice.common.DiceEvent;
import example.dice.common.ScriptedChatModel;
import example.dice.common.ScriptedLlm;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.event.EventListener;
import org.springframework.core.io.ClassPathResource;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * What the tests add to the application: the model they run against, and a record of the
 * DICE events published while they run.
 */
@TestConfiguration(proxyBeanMethods = false)
class ScriptedModel {

    /**
     * Replays one recorded reply per ticket, matched on the ticket's opening words, which the
     * extraction prompt contains.
     *
     * @return the scripted model
     */
    @Bean
    ScriptedChatModel scriptedChatModel() {
        return ScriptedChatModel.script()
                .whenPromptContains("Ada Okafor from Acme wrote in", new ClassPathResource("recorded/ticket-1042.json"))
                .whenPromptContains("Follow-up with Ada Okafor", new ClassPathResource("recorded/ticket-1057.json"))
                .whenPromptContains("Ben Ruiz at Globex", new ClassPathResource("recorded/ticket-2210.json"))
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
     * Keeps every DICE event the application publishes.
     *
     * @return the record the tests read
     */
    @Bean
    DiceEvents recordedDiceEvents() {
        return new DiceEvents();
    }

    /**
     * The DICE events published so far.
     */
    static class DiceEvents {

        private final List<DiceEvent> events = new CopyOnWriteArrayList<>();

        /**
         * Records one event.
         *
         * @param event the event the pipeline published
         */
        @EventListener
        void on(DiceEvent event) {
            events.add(event);
        }

        /**
         * The recorded events of one type.
         *
         * @param type the event type to keep
         * @return the matching events, oldest first
         */
        <T extends DiceEvent> List<T> ofType(Class<T> type) {
            return events.stream().filter(type::isInstance).map(type::cast).toList();
        }
    }
}
