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
package com.embabel.dice.conformance.example;

import com.embabel.dice.conformance.AbstractCollectorTraceStoreContractTest;
import com.embabel.dice.spi.CollectorTraceStore;
import com.embabel.dice.spi.InMemoryCollectorTraceStore;

/** Runs the collector trace suite from Java over the in-memory store, which is also its read side. */
class CollectorTraceStoreContractJavaInteropTest extends AbstractCollectorTraceStoreContractTest {

    @Override
    protected CollectorTraceStore store() {
        return new InMemoryCollectorTraceStore();
    }
}
