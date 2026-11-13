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

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.core.io.Resource;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * A chat model that replies from a script. Each rule pairs a piece of text with a reply, and
 * the model gives the reply of the first rule whose text the prompt contains.
 *
 * <p>The examples register it with Embabel Agent as the default model. DICE renders its prompt
 * template and parses the reply the same way it does with a live model.
 *
 * <p>A prompt that matches no rule throws {@link IllegalStateException}.
 */
public final class ScriptedChatModel implements ChatModel {

    /**
     * One line of the script.
     *
     * @param promptFragment text to look for in the prompt
     * @param reply          what to answer when it is found
     */
    private record Rule(String promptFragment, String reply) {
    }

    private final List<Rule> rules;
    private final List<String> promptsSeen = Collections.synchronizedList(new ArrayList<>());

    /**
     * @param rules the script, in the order the rules are tried
     */
    private ScriptedChatModel(List<Rule> rules) {
        this.rules = List.copyOf(rules);
    }

    /**
     * Starts a script.
     *
     * @return a builder with no rules
     */
    public static Builder script() {
        return new Builder();
    }

    @Override
    public ChatResponse call(Prompt prompt) {
        var text = prompt.getContents();
        promptsSeen.add(text);
        for (var rule : rules) {
            if (text.contains(rule.promptFragment())) {
                return new ChatResponse(List.of(new Generation(new AssistantMessage(rule.reply()))));
            }
        }
        throw new IllegalStateException(
                "No scripted reply matches this prompt. Add a rule for it. The prompt ends with: ..."
                        + text.substring(Math.max(0, text.length() - 400)));
    }

    /**
     * Every prompt the model was sent.
     *
     * @return the prompts, oldest first
     */
    public List<String> promptsSeen() {
        return List.copyOf(promptsSeen);
    }

    public static final class Builder {

        private final List<Rule> rules = new ArrayList<>();

        private Builder() {
        }

        /**
         * Replies with {@code reply} whenever a prompt contains {@code promptFragment}. Rules
         * are tried in the order they were added, and the first match wins.
         *
         * @param promptFragment text to look for anywhere in the prompt
         * @param reply          what the model answers
         * @return this builder
         * @throws IllegalArgumentException if {@code promptFragment} is blank
         */
        public Builder whenPromptContains(String promptFragment, String reply) {
            if (promptFragment.isBlank()) {
                throw new IllegalArgumentException("promptFragment must not be blank");
            }
            rules.add(new Rule(promptFragment, reply));
            return this;
        }

        /**
         * Replies with a recorded reply kept in a file. The examples keep theirs under
         * {@code src/test/resources/recorded/}, one file per model call, and pass a
         * {@code ClassPathResource}.
         *
         * @param promptFragment text to look for anywhere in the prompt
         * @param recording      the file holding what the model answers, read as UTF-8
         * @return this builder
         * @throws IllegalArgumentException if {@code promptFragment} is blank
         * @throws UncheckedIOException     if the recording is missing or cannot be read
         */
        public Builder whenPromptContains(String promptFragment, Resource recording) {
            try {
                return whenPromptContains(promptFragment, recording.getContentAsString(StandardCharsets.UTF_8));
            } catch (IOException e) {
                throw new UncheckedIOException("Cannot read the recorded reply in " + recording.getDescription(), e);
            }
        }

        /**
         * Builds the model.
         *
         * @return a model that replies from the rules added so far
         */
        public ScriptedChatModel build() {
            return new ScriptedChatModel(rules);
        }
    }
}
