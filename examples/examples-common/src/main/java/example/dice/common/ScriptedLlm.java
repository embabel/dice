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
package example.dice.common;

import com.embabel.agent.spi.LlmService;
import com.embabel.agent.spi.support.springai.SpringAiLlmService;

/**
 * Registers a {@link ScriptedChatModel} with Embabel Agent under a fixed model name.
 *
 * <p>Expose the result as a Spring bean and set {@code embabel.models.default-llm} to
 * {@link #MODEL_NAME}. Embabel Agent then uses the scripted model as its default.
 */
public final class ScriptedLlm {

    /** The model name to put in {@code embabel.models.default-llm}. */
    public static final String MODEL_NAME = "scripted";

    private static final String PROVIDER = "dice-examples";

    private ScriptedLlm() {
    }

    /**
     * Wraps a scripted chat model as an Embabel Agent LLM service.
     *
     * @param model the scripted model to serve
     * @return a service Embabel picks up when it is registered as a bean
     */
    public static LlmService<?> service(ScriptedChatModel model) {
        return new SpringAiLlmService(MODEL_NAME, PROVIDER, model);
    }
}
