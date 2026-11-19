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
package com.embabel.dice.proposition.extraction

import org.jetbrains.annotations.ApiStatus

/**
 * A direct call to [IncrementalPropositionExtraction.extractPropositions] waited too long for
 * another extraction to finish, so its event was not run.
 *
 * It is safe to retry. Nothing of the event was stored and its window is still unrecorded. The
 * wait is bounded because a caller inside a transaction may hold a database lock that the running
 * extraction needs; giving up lets that transaction roll back and release the lock instead of both
 * waiting. The type is unchecked, so a Spring `@Transactional` method rolls back on it by default.
 *
 * From Java:
 *
 * ```java
 * try {
 *     extraction.extractPropositions(event);
 * } catch (ExtractionBusyException e) {
 *     // Roll back, then publish or call again later.
 * }
 * ```
 *
 * EXPERIMENTAL.
 */
@ApiStatus.Experimental
class ExtractionBusyException(message: String) : RuntimeException(message)
