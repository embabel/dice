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
package com.embabel.dice.projection.lineage

import com.embabel.dice.proposition.Proposition

/**
 * [Reconciler] that always returns [ReconciliationDecision.CreateNew], the
 * projection counterpart of
 * [com.embabel.dice.common.resolver.AlwaysCreateEntityResolver].
 *
 * Useful as a default implementation and in tests. It performs no matching
 * against existing artifacts, so it is not suitable where duplication within a
 * target store must be avoided.
 */
object AlwaysCreateReconciler : Reconciler {

    override fun reconcile(proposition: Proposition, target: String): ReconciliationDecision =
        ReconciliationDecision.CreateNew
}
