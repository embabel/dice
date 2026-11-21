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
package example.dice.backends;

import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * A small memory desk that stores facts about the companies it deals with.
 *
 * <p>The only storage code is {@link MemoryDesk}, which talks to DICE's
 * {@code PropositionRepository}. The example defaults to the in-memory store.
 * {@link GraphStoreConfiguration} supplies the connection for the explicit Neo4j setup.
 * The SQLite setup clears the example's store override and lets the starter use the configured
 * SQLite datasource.
 */
@SpringBootApplication
class BackendSwapApplication {
}
