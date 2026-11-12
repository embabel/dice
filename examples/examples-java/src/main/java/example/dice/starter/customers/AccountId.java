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
package example.dice.starter.customers;

import org.jmolecules.ddd.types.Identifier;

/**
 * Identifies one customer account.
 *
 * <p>The account id is the DICE context id. Facts are stored under it and queried by it.
 *
 * @param value the account's id, for example {@code acme}
 */
public record AccountId(String value) implements Identifier {

    /**
     * @throws IllegalArgumentException if {@code value} is blank
     */
    public AccountId {
        if (value.isBlank()) {
            throw new IllegalArgumentException("An account id must not be blank");
        }
    }
}
