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

import com.embabel.dice.store.StoreCapabilities;
import com.embabel.dice.store.StoreCapability;
import com.embabel.dice.store.VectorFiltering;
import org.junit.jupiter.api.Test;

import java.sql.SQLException;
import java.util.EnumSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A store dialect written in Java, the way a Java developer adding a database would write one.
 */
class StoreDialectJavaInteropTest {

    private static final class ExampleDialect implements StoreDialect {

        @Override
        public StoreCapabilities getCapabilities() {
            return StoreCapabilities.of(StoreCapability.ATOMIC_WRITES)
                    .withVectorFiltering(VectorFiltering.AFTER_TOP_K);
        }

        @Override
        public Set<DialectFeature> getFeatures() {
            return EnumSet.noneOf(DialectFeature.class);
        }

        @Override
        public boolean isUniquenessViolation(Throwable failure) {
            for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
                if (cause instanceof SQLException sql && "23505".equals(sql.getSQLState())) {
                    return true;
                }
            }
            return false;
        }
    }

    private final StoreDialect dialect = new ExampleDialect();

    @Test
    void aDialectPublishesItsCapabilitiesAndFeatures() {
        assertEquals(VectorFiltering.AFTER_TOP_K, dialect.getCapabilities().getVectorFiltering());
        assertTrue(dialect.getCapabilities().supports(StoreCapability.ATOMIC_WRITES));
        assertFalse(dialect.getFeatures().contains(DialectFeature.DYNAMIC_LABELS));
    }

    @Test
    void aStoreWithNoSimilaritySearchNarrowsTheDialectsCapabilities() {
        StoreCapabilities runStore = dialect.getCapabilities().withoutSimilaritySearch();

        assertEquals(StoreCapabilities.of(StoreCapability.ATOMIC_WRITES), runStore);
        assertEquals(VectorFiltering.NONE, runStore.getVectorFiltering());
    }

    @Test
    void aDialectTellsAUniquenessViolationFromAnotherFailure() {
        assertTrue(dialect.isUniquenessViolation(new SQLException("duplicate key", "23505")));
        assertTrue(dialect.isUniquenessViolation(
                new IllegalStateException("write failed", new SQLException("duplicate key", "23505"))));
        assertFalse(dialect.isUniquenessViolation(new SQLException("syntax error", "42601")));
        assertFalse(dialect.isUniquenessViolation(new IllegalStateException("boom")));
    }
}
