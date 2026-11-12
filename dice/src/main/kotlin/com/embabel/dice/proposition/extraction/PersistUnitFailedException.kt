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
 * A unit failed at a step that threw a checked exception.
 *
 * [PersistOutcome.persistedOrThrow] throws it. The exception the step threw is the [cause]. The type
 * is unchecked, so a Java caller needs no `throws` clause and a Spring `@Transactional` method rolls
 * back on it by default.
 *
 * From Java:
 *
 * ```java
 * try {
 *     outcome.persistedOrThrow();
 * } catch (PersistUnitFailedException e) {
 *     logger.error("Storing failed at {}", e.getStep(), e.getCause());
 * }
 * ```
 *
 * From Kotlin:
 *
 * ```kotlin
 * try {
 *     outcome.persistedOrThrow()
 * } catch (e: PersistUnitFailedException) {
 *     logger.error("Storing failed at {}", e.step, e.cause)
 * }
 * ```
 *
 * EXPERIMENTAL. The API may change before 1.0.
 *
 * @property step The step that threw
 * @param cause The checked exception the step threw.
 */
@ApiStatus.Experimental
class PersistUnitFailedException(
    val step: PersistStep,
    cause: Exception,
) : RuntimeException("persist unit failed at $step", cause)
