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
package com.embabel.dice.storage

import com.embabel.dice.metamodel.InMemoryMetamodelVersionStore
import com.embabel.dice.metamodel.MetamodelVersionStore

/**
 * Runs the [AbstractMetamodelVersionStoreContractTest] suite against the in-memory reference store.
 * No Docker, so it runs in the normal test phase — the always-on half of the cross-backend check
 * the graph IT completes.
 */
class InMemoryMetamodelVersionStoreContractTest : AbstractMetamodelVersionStoreContractTest() {
    override fun store(): MetamodelVersionStore = InMemoryMetamodelVersionStore()
}
