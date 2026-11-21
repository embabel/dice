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

import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * A support desk that remembers what its customers say in chat.
 *
 * <p>{@code SupportSchema} names the kinds of thing a chat can mention, {@code SupportMemory}
 * wires DICE and its in-memory stores, and {@code SupportDesk} runs each chat through them.
 *
 * <p>This class is the Spring Boot configuration the tests start.
 */
@SpringBootApplication
class SupportDomainApplication {
}
