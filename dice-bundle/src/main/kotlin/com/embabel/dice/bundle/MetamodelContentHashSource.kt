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
package com.embabel.dice.bundle

import com.embabel.agent.core.ContextId
import org.jetbrains.annotations.ApiStatus

/**
 * Supplies the current metamodel content hash for a context without coupling `dice-bundle` to the
 * `dice-metamodel` module. Returning `null` means no metamodel stamp is available.
 */
@ApiStatus.Experimental
fun interface MetamodelContentHashSource {

    fun contentHash(contextId: ContextId): String?
}

/** Default source for consumers that do not use metamodel governance. */
@ApiStatus.Experimental
object NoOpMetamodelContentHashSource : MetamodelContentHashSource {

    override fun contentHash(contextId: ContextId): String? = null
}
