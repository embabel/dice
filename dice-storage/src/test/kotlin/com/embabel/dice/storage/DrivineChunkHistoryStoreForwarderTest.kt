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

import com.embabel.agent.core.ContextId
import org.drivine.manager.PersistenceManager
import org.drivine.manager.StatelessGraphObjectManager
import org.drivine.query.QuerySpecification
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.mockito.Mockito.mock
import org.mockito.Mockito.verifyNoInteractions
import kotlin.test.assertEquals

/*
 * Contract for DrivineChunkHistoryStore.clearByContext(ContextId), the typed forwarder.
 *
 * R1: clearByContext takes a ContextId and reads its value as the context to clear.
 * E1: clearByContext runs exactly one delete query through the PersistenceManager.
 * E2: clearByContext binds ContextId("acme") as the contextId parameter of that query.
 * I1: clearing by context never touches the StatelessGraphObjectManager.
 */
class DrivineChunkHistoryStoreForwarderTest {

    private val graphObjectManager = mock(StatelessGraphObjectManager::class.java)
    private val persistenceManager = mock(PersistenceManager::class.java)
    private val store = DrivineChunkHistoryStore(graphObjectManager, persistenceManager)

    // E1 and E2: the typed forwarder runs one delete query, binding the context id value.
    // Argument matchers return null in Kotlin, so the recorded calls are read back directly.
    @Test
    fun `clearByContext runs one delete query binding the context id value`() {
        store.clearByContext(ContextId("acme"))

        val executeCalls = Mockito.mockingDetails(persistenceManager).invocations
            .filter { it.method.name == "execute" }
        assertEquals(1, executeCalls.size)
        val query = executeCalls.single().arguments[0] as QuerySpecification<*>
        assertEquals("acme", query.parameters["contextId"])
    }

    // R1 and I1: clearing by context only runs the delete query and never reads or writes graph objects.
    @Test
    fun `clearByContext leaves the graph object manager alone`() {
        store.clearByContext(ContextId("acme"))

        verifyNoInteractions(graphObjectManager)
    }
}
