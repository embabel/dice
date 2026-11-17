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
package com.embabel.dice.conformance

import com.embabel.dice.proposition.PropositionStore
import com.embabel.dice.store.VectorFiltering

/**
 * Runs the [AbstractFilteredVectorSearchContractTest] suite against [AfterTopKReferenceStore]. It
 * needs no Docker, so it runs in the normal test phase.
 */
class AfterTopKReferenceStoreContractTest : AbstractFilteredVectorSearchContractTest() {
    override fun store(): PropositionStore = AfterTopKReferenceStore()

    override fun expectedVectorFiltering(): VectorFiltering = VectorFiltering.AFTER_TOP_K
}
