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

import com.embabel.dice.conformance.AbstractProjectionRecordStoreContractTest;
import com.embabel.dice.projection.lineage.ProjectionRecord;
import com.embabel.dice.projection.lineage.ProjectionRecordStore;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

/**
 * A test for a {@code ProjectionRecordStore} written in Java that keeps one interface default, with
 * the case that default fails turned off.
 */
class ProjectionRecordStoreContractJavaInteropTest extends AbstractProjectionRecordStoreContractTest {

    @Override
    protected ProjectionRecordStore store() {
        return new AppendOnlyProjectionRecordStore();
    }

    @Test
    @Disabled("AppendOnlyProjectionRecordStore keeps the interface default for markStaleByProposition, which marks nothing")
    @Override
    public void markStaleByPropositionMarksEveryRecordOfTheProposition() {
    }

    /** Keeps records in a list and inherits every finder and markStaleByProposition from the interface. */
    static final class AppendOnlyProjectionRecordStore implements ProjectionRecordStore {

        private final List<ProjectionRecord> records = new ArrayList<>();

        @Override
        public void record(ProjectionRecord record) {
            records.add(record);
        }

        @Override
        public List<ProjectionRecord> all() {
            return List.copyOf(records);
        }
    }
}
