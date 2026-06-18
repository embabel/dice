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
package com.embabel.dice.proposition.store

import com.embabel.agent.core.ContextId
import com.embabel.dice.proposition.DecayManager
import com.embabel.dice.proposition.PropositionRepository

/**
 * [DecayManager] for non-caching backends: lifecycle transitions only. Materialisation is a no-op
 * because the backing repository computes effective confidence on the fly — there is no persisted
 * ranking column to refresh. Pairs with [InMemoryPropositionRepository].
 */
class InMemoryDecayManager(
    repository: PropositionRepository,
) : DecayManager(repository) {

    override fun materialize(contextId: ContextId) {
        // No persisted effectiveConfidence to refresh.
    }

    override fun materializeAll() {
        // No persisted effectiveConfidence to refresh.
    }
}
