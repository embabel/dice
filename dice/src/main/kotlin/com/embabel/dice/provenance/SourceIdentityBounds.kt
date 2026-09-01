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
package com.embabel.dice.provenance

/**
 * Length ceilings for the two strings that arrive from outside DICE and end up inside stored
 * identity: the canonical source key from [SourceLocator.key], and a provider-defined source
 * revision.
 *
 * Both get hashed into an evidence key and written to an indexed graph property, so an unbounded
 * value is a real hazard: it inflates every index entry that mentions it, and a graph store has its
 * own hard ceiling on an indexed property that surfaces as an opaque database error deep inside a
 * write. Checking here turns that into an [IllegalArgumentException] at the moment the value is
 * offered, before anything is hashed and before any store is touched.
 *
 * The limits are deliberately roomy. Nothing a real connector produces comes close; they exist to
 * stop a runaway or hostile value, and they are stated as constants so a caller can check its own
 * input against the same number DICE will.
 */
object SourceIdentityBounds {

    /**
     * Longest accepted canonical source key.
     *
     * A key is a short kind prefix plus whatever the locator wraps, and the longest of those is a
     * URI. 2048 characters is the practical ceiling browsers and proxies have settled on for a URL,
     * which leaves generous headroom over any URI, file path, content hash, or connector id pair
     * that shows up in practice.
     */
    const val MAX_SOURCE_KEY_LENGTH: Int = 2048

    /**
     * Longest accepted source revision.
     *
     * A revision is an opaque token from whichever provider owns the source: a git SHA is 40 or 64
     * characters, an HTTP ETag a few dozen, a Slack or Notion timestamp about 20. The longest real
     * one we know of is an S3 object version id, which AWS caps at 1024, so that is the number.
     */
    const val MAX_SOURCE_REVISION_LENGTH: Int = 1024

    /**
     * Check a canonical source key against [MAX_SOURCE_KEY_LENGTH] and hand it back unchanged.
     *
     * @param sourceKey the key to check
     * @return [sourceKey], for use inline in a constructor
     * @throws IllegalArgumentException if the key is longer than [MAX_SOURCE_KEY_LENGTH]
     */
    @JvmStatic
    fun requireSourceKeyWithinBounds(sourceKey: String): String {
        require(sourceKey.length <= MAX_SOURCE_KEY_LENGTH) {
            "source key is ${sourceKey.length} characters, over the " +
                "SourceIdentityBounds.MAX_SOURCE_KEY_LENGTH limit of $MAX_SOURCE_KEY_LENGTH"
        }
        return sourceKey
    }

    /**
     * Check a source revision against [MAX_SOURCE_REVISION_LENGTH] and hand it back unchanged.
     *
     * @param sourceRevision the revision to check
     * @return [sourceRevision], for use inline in a constructor
     * @throws IllegalArgumentException if the revision is longer than [MAX_SOURCE_REVISION_LENGTH]
     */
    @JvmStatic
    fun requireSourceRevisionWithinBounds(sourceRevision: String): String {
        require(sourceRevision.length <= MAX_SOURCE_REVISION_LENGTH) {
            "source revision is ${sourceRevision.length} characters, over the " +
                "SourceIdentityBounds.MAX_SOURCE_REVISION_LENGTH limit of $MAX_SOURCE_REVISION_LENGTH"
        }
        return sourceRevision
    }
}
