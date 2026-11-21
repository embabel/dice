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
package example.dice.starter.triage;

import org.jspecify.annotations.Nullable;

/**
 * How a ticket was triaged.
 *
 * <p>Each answer is null when the model could not give a plain one, for example because it was
 * unsure, none of the options fitted, or the call failed.
 *
 * @param urgent      whether the customer needs help today
 * @param department  the team that should handle the ticket: {@code billing}, {@code technical}
 *                    or {@code account}
 * @param frustration how frustrated the customer sounds: {@code calm}, {@code annoyed} or
 *                    {@code angry}
 * @param needsHuman  true when any of the three answers is missing, so a person should look at
 *                    the ticket
 * @param input       the text the model was shown: the ticket followed by the customer's facts
 */
public record Triage(
        @Nullable Boolean urgent,
        @Nullable String department,
        @Nullable String frustration,
        boolean needsHuman,
        String input) {
}
