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

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.core.io.ClassPathResource;

import java.io.UncheckedIOException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ScriptedChatModelTests {

    @Test
    void repliesWithTheFirstRuleThatMatches() {
        var model = ScriptedChatModel.script()
                .whenPromptContains("ticket 1042", "first")
                .whenPromptContains("ticket", "second")
                .build();

        assertThat(model.call(new Prompt("About ticket 1042")).getResult().getOutput().getText())
                .isEqualTo("first");
        assertThat(model.call(new Prompt("About ticket 7")).getResult().getOutput().getText())
                .isEqualTo("second");
        assertThat(model.promptsSeen()).containsExactly("About ticket 1042", "About ticket 7");
    }

    @Test
    void failsWhenNoRuleMatches() {
        var model = ScriptedChatModel.script().whenPromptContains("ticket", "reply").build();

        assertThatThrownBy(() -> model.call(new Prompt("Something else entirely")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("No scripted reply matches");
    }

    @Test
    void repliesWithARecordingFromTheClasspath() {
        var model = ScriptedChatModel.script()
                .whenPromptContains("ticket", new ClassPathResource("recorded/sample.json"))
                .build();

        assertThat(model.call(new Prompt("About ticket 7")).getResult().getOutput().getText())
                .contains("\"propositions\"");
    }

    @Test
    void namesTheRecordingThatIsMissing() {

        assertThatThrownBy(() -> ScriptedChatModel.script()
                .whenPromptContains("ticket", new ClassPathResource("recorded/nope.json")))
                .isInstanceOf(UncheckedIOException.class)
                .hasMessageContaining("recorded/nope.json");
    }
}
