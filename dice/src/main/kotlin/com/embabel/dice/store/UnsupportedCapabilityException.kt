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
package com.embabel.dice.store

import org.jetbrains.annotations.ApiStatus

/**
 * Thrown when a store is asked for something it cannot do.
 *
 * A store refuses by throwing this. It does not return an empty or partial result, and it does not
 * finish the work in the JVM, because a caller cannot tell those from a real answer. A caller that
 * gets a capability through [StoreCapabilities.find] sees it only when the store cannot apply a
 * field of a request's filter.
 *
 * ```java
 * throw new UnsupportedCapabilityException(
 *     StoreCapability.FULL_TEXT_SEARCH, "this store cannot filter text search by effective confidence");
 * ```
 *
 * EXPERIMENTAL. This API may change before 1.0.
 *
 * @property capability The capability that was asked for.
 * @param reason Why this store cannot do it, in one sentence.
 * @param cause The failure underneath, when there is one.
 */
@ApiStatus.Experimental
class UnsupportedCapabilityException @JvmOverloads constructor(
    val capability: StoreCapability,
    reason: String,
    cause: Throwable? = null,
) : UnsupportedOperationException("$capability is not supported: $reason", cause)
