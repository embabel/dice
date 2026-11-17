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
package com.embabel.dice.common

/**
 * Runs [block] and wraps the outcome in a [Result]. An exception becomes a failure. If the call
 * was interrupted, the thread's interrupt flag is set again so the caller can still see it. An
 * error is not caught and keeps going up the stack.
 */
internal inline fun <T> attemptCatching(block: () -> T): Result<T> {
    return try {
        Result.success(block())
    } catch (e: InterruptedException) {
        Thread.currentThread().interrupt()
        Result.failure(e)
    } catch (e: Exception) {
        Result.failure(e)
    }
}
