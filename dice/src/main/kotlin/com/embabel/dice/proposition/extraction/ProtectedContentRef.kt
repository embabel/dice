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
import java.time.Instant

/**
 * The shape of a reference to failure material a host keeps for itself. Specification only.
 *
 * [ExtractionFailure] speaks a closed vocabulary of codes, stages and numbers, so an exception
 * message, a response body, or the fragment that failed to parse has no route into anything DICE
 * stores. Some of that material is still worth keeping, and a host that wants it keeps it in its
 * own vault under its own rules. This interface says what such a reference looks like when the
 * host passes one around its own code.
 *
 * **DICE implements none of this and holds none of it.** There is no implementation in this
 * repository, nothing in DICE constructs or accepts one, and no run, failure, or stored row has a
 * field of this type. It is here so a host writing that vault has a written contract to work from.
 *
 * ## The three jobs the host owns
 *
 * **The writer is the host's.** Whatever puts the material in the vault is host code. DICE never
 * sees the material, so it can never be the thing that writes it.
 *
 * **The reader is the host's.** Resolving [handle] back to material is a host operation, subject to
 * the host's own access rules. DICE never resolves a handle and never presents one to anything;
 * the handle names a row in the host's store, and it is not a way to reach that row. A handle that
 * is a signed URL, a pre-authenticated link, a bearer token, a decryption key, or a path on a share
 * breaks the contract, because holding a reference has to grant nothing.
 *
 * **Retention is the host's, and [expiresAt] is where the host writes it down.** The material is
 * expected to be gone by that instant. Nothing in DICE sweeps, deletes, or checks it, so the
 * declaration is worth exactly what the host's retention job makes it worth. An erasure request
 * that has to reach this material reaches it through the host's vault. A reference whose expiry has
 * passed stays meaningful as history: a failure that had detail until March and has none now is a
 * fact about that failure.
 *
 * ## A worked example
 *
 * A host wants the provider's exception message for the ninety days its incident process runs on.
 * It mints a handle, writes the message into its own vault under that handle, and keeps the
 * reference beside its own incident record. The failure DICE stores carries the code, the stage and
 * the invocation, and none of the message.
 *
 * ```kotlin
 * class VaultedFailureDetail(
 *     override val handle: String,
 *     override val expiresAt: Instant,
 * ) : ProtectedContentRef
 *
 * // Host code, in the host's own application:
 * fun recordDetail(vault: DetailVault, thrown: Throwable): ProtectedContentRef {
 *     val handle = "pcr:" + UUID.randomUUID()
 *     val expiresAt = Instant.now().plus(90, ChronoUnit.DAYS)
 *     vault.put(handle, thrown.stackTraceToString(), expiresAt)  // the host's store, the host's rules
 *     return VaultedFailureDetail(handle, expiresAt)
 * }
 *
 * // The host's nightly retention job deletes vault rows whose expiresAt has passed.
 * // DICE is not involved in any line above.
 * ```
 *
 * EXPERIMENTAL. The shape may still change while extraction runs (DICE #67) land.
 *
 * @property handle The host's opaque name for the material. It identifies a row in the host's
 *   vault and grants no access to it.
 * @property expiresAt When the host's retention job is expected to have removed the material
 */
@ApiStatus.Experimental
interface ProtectedContentRef {

    /** The host's opaque name for the material. */
    val handle: String

    /** When the host's own retention job is expected to have removed the material. */
    val expiresAt: Instant
}
