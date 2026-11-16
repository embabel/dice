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
package com.embabel.dice.storage;

import org.drivine.manager.PersistenceManager;
import org.drivine.manager.StatelessGraphObjectManager;
import org.drivine.query.QuerySpecification;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/*
 * Contract for DrivineChunkHistoryStore's context-wide clear, called the way a Java caller would.
 *
 * R1: clearByContextValue takes any context id string and binds it as the contextId parameter.
 * E1: clearByContextValue runs exactly one delete query through the PersistenceManager.
 * E2: clearByContextValue("acme") binds "acme" as the contextId parameter of that query.
 * I1: clearing by context never touches the StatelessGraphObjectManager.
 */
class DrivineChunkHistoryStoreJavaTest {

    private final StatelessGraphObjectManager graphObjectManager = mock(StatelessGraphObjectManager.class);
    private final PersistenceManager persistenceManager = mock(PersistenceManager.class);
    private final DrivineChunkHistoryStore store =
            new DrivineChunkHistoryStore(graphObjectManager, persistenceManager);

    // E1 and E2: one delete query, with the raw context id bound as contextId.
    @Test
    void clearByContextValueRunsOneDeleteQueryBindingTheContextId() {
        store.clearByContextValue("acme");

        ArgumentCaptor<QuerySpecification> captor = ArgumentCaptor.forClass(QuerySpecification.class);
        verify(persistenceManager, times(1)).execute(captor.capture());
        assertEquals("acme", captor.getValue().getParameters().get("contextId"));
    }

    // R1 and E1: a different string gives a different bound value, so the value is not hard-coded.
    @Test
    void clearByContextValueBindsWhateverStringItIsGiven() {
        store.clearByContextValue("other-context");

        ArgumentCaptor<QuerySpecification> captor = ArgumentCaptor.forClass(QuerySpecification.class);
        verify(persistenceManager, times(1)).execute(captor.capture());
        assertEquals("other-context", captor.getValue().getParameters().get("contextId"));
    }

    // I1: clearing never reads or writes graph objects, only runs the delete query.
    @Test
    void clearByContextValueLeavesGraphObjectManagerAlone() {
        store.clearByContextValue("acme");

        verifyNoInteractions(graphObjectManager);
    }
}
