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
package com.embabel.dice.projection.graph

import com.embabel.dice.proposition.Proposition

/**
 * Builds a human-readable explanation for why a proposition was rejected by a projection policy.
 *
 * Checks two common policy gates: confidence below the default threshold (0.85) and
 * unresolved entity mentions. Returns a comma-separated summary, or "policy criteria not met"
 * when neither specific gate fired (i.e. the policy has its own logic not reflected here).
 */
internal fun Proposition.policyRejectionReason(): String {
    val reasons = mutableListOf<String>()
    if (confidence < 0.85) {
        reasons.add("low confidence ($confidence)")
    }
    if (!isFullyResolved()) {
        val unresolved = mentions.filter { it.resolvedId == null }.map { it.span }
        reasons.add("unresolved entities: $unresolved")
    }
    return reasons.joinToString(", ").ifEmpty { "policy criteria not met" }
}
