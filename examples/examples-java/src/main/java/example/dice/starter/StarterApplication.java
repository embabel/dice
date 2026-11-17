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
package example.dice.starter;

import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * A small support desk that remembers what its tickets say.
 *
 * <p>Each package below this one is a module. {@code tickets} files tickets and publishes an
 * event for each. {@code memory} listens, extracts facts from the ticket and stores them.
 * {@code customers} holds the types both use.
 *
 * <p>This class is the Spring Boot configuration the tests start. {@code TicketMemoryTests}
 * boots it with a scripted model in place of a live one.
 */
@SpringBootApplication
class StarterApplication {
}
