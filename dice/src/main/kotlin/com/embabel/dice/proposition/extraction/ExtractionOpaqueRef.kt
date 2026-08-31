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
 * A bounded, host-minted token that names something about a run without describing it.
 *
 * DICE compares these and stores them and parses nothing out of them. Every one of them exists so
 * an audit can ask "which runs share this actor?" or "which runs ran under this deployment?"
 * without DICE holding a user object, a session object, or a personalization payload.
 *
 * **The contract a host takes on when it mints one:**
 * - It is a pseudonym. Not an email address, not a username, not a phone number, not a customer
 *   number, not a name — nothing that identifies a person on its own.
 * - It is not dereferenceable into anything sensitive. Not a URL, not a signed link, not a bearer
 *   token, not an API key, not a session cookie value.
 * - It carries no authorization. Holding one grants nothing; DICE never presents it to anything.
 * - It is stable enough to group by and cheap enough to rotate. A host that wants to break the
 *   link between a subject and its past runs rotates the token, and the old runs stay grouped
 *   among themselves.
 *
 * **What the type can enforce, and what it cannot.** Construction bounds the length and restricts
 * the characters to `A-Z a-z 0-9 . _ : ~ -`, which rules out whitespace, control characters,
 * `@`, `/` and `\` — so an email address, a URL, a file path and a human name are all rejected
 * outright, and the common shapes of a leaked identifier cannot be stored. It cannot tell a
 * pseudonym from a username, or a random token from a customer number: `jdunnam` and `55512345`
 * both pass. The last mile of that contract is the host's, and the design note says so in the same
 * words.
 *
 * [toString] shows only the first [TOKEN_PREVIEW_LENGTH] characters, so a token does not spread
 * through logs and exception messages in full. Validation messages never quote the token at all.
 *
 * Equality is by exact type and token together, so an actor token and a session token that happen
 * to hold the same string are two different references.
 *
 * EXPERIMENTAL. The shape may still change while extraction runs (DICE #67) land.
 *
 * @property token The opaque value the host minted
 */
@ApiStatus.Experimental
sealed class ExtractionOpaqueRef(val token: String) {

    init {
        require(token.isNotBlank()) { "${javaClass.simpleName} token must not be blank" }
        require(token.length <= ExtractionRunLimits.MAX_IDENTIFIER_LENGTH) {
            "${javaClass.simpleName} token must be at most " +
                "${ExtractionRunLimits.MAX_IDENTIFIER_LENGTH} characters, was ${token.length}"
        }
        require(TOKEN_PATTERN.matches(token)) {
            "${javaClass.simpleName} token must contain only letters, digits and . _ : ~ - " +
                "so that an address, a URL, a path or a name cannot be stored as one"
        }
    }

    final override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other == null || javaClass != other.javaClass) return false
        return token == (other as ExtractionOpaqueRef).token
    }

    final override fun hashCode(): Int = 31 * javaClass.hashCode() + token.hashCode()

    final override fun toString(): String =
        "${javaClass.simpleName}(token=${token.take(TOKEN_PREVIEW_LENGTH)}…)"

    companion object {

        /** Characters an opaque token may contain. */
        private val TOKEN_PATTERN = Regex("[A-Za-z0-9._:~-]+")

        /** How much of a token [toString] shows: enough to correlate two lines, not the value. */
        const val TOKEN_PREVIEW_LENGTH: Int = 8
    }
}

/**
 * Names whoever the run acted for, pseudonymously.
 *
 * This is the reference an audit groups by to answer "what was extracted on this person's
 * behalf?" without DICE ever holding who that person is.
 */
@ApiStatus.Experimental
class ExtractionActorRef(token: String) : ExtractionOpaqueRef(token)

/**
 * Names the inbound request the run was started for.
 *
 * A host that already carries a request or correlation id through its own logs passes the same one
 * here, so a run lines up with the request that caused it.
 */
@ApiStatus.Experimental
class ExtractionRequestRef(token: String) : ExtractionOpaqueRef(token)

/**
 * Names the conversation or session the material came from.
 *
 * Several runs over one long conversation share this reference, which is what makes "everything
 * extracted from that session" a single query.
 */
@ApiStatus.Experimental
class ExtractionSessionRef(token: String) : ExtractionOpaqueRef(token)

/**
 * Names the personalization state in force, without holding any of it.
 *
 * A host that varies extraction by user preferences, memory, or tuned instructions fingerprints
 * that state and passes the fingerprint. Two runs with the same reference ran under the same
 * personalization; what it contained stays with the host.
 */
@ApiStatus.Experimental
class ExtractionPersonalizationRef(token: String) : ExtractionOpaqueRef(token)

/**
 * Names the deployment the run executed in — an environment, a region, a release, a shard.
 *
 * This is what separates "the model got worse" from "the model got worse in one deployment".
 */
@ApiStatus.Experimental
class ExtractionDeploymentRef(token: String) : ExtractionOpaqueRef(token)

/**
 * Names the experiment a run belongs to.
 *
 * A label to group and compare by. It carries the same no-personal-data contract as the rest of
 * the family, because an experiment name is a place where a description of the subjects tends to
 * end up.
 */
@ApiStatus.Experimental
class ExtractionExperimentRef(token: String) : ExtractionOpaqueRef(token)

/**
 * Names the arm or cohort within an experiment.
 *
 * Same contract as [ExtractionExperimentRef]: a label, not a description of who is in it.
 */
@ApiStatus.Experimental
class ExtractionCohortRef(token: String) : ExtractionOpaqueRef(token)
