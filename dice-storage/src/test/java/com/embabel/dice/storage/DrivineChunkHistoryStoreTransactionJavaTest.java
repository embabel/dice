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
import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;

/*
 * Checks that the Java entry point of DrivineChunkHistoryStore runs inside a transaction when the
 * store is a Spring proxy, as it is when Spring wires it.
 *
 * E1: clearByContextValue on a proxied store begins and commits exactly one transaction.
 */
class DrivineChunkHistoryStoreTransactionJavaTest {

    /** A transaction manager that only records the transactions it was asked to begin and commit. */
    static final class RecordingTransactionManager extends AbstractPlatformTransactionManager {

        final List<String> events = new ArrayList<>();

        @Override
        protected Object doGetTransaction() {
            return new Object();
        }

        @Override
        protected void doBegin(Object transaction, TransactionDefinition definition) {
            events.add("begin");
        }

        @Override
        protected void doCommit(DefaultTransactionStatus status) {
            events.add("commit");
        }

        @Override
        protected void doRollback(DefaultTransactionStatus status) {
            events.add("rollback");
        }
    }

    private final RecordingTransactionManager transactions = new RecordingTransactionManager();

    private DrivineChunkHistoryStore proxiedStore() {
        DrivineChunkHistoryStore target = new DrivineChunkHistoryStore(
                mock(StatelessGraphObjectManager.class), mock(PersistenceManager.class));
        ProxyFactory factory = new ProxyFactory(target);
        factory.setProxyTargetClass(true);
        factory.addAdvice(new TransactionInterceptor(transactions, new AnnotationTransactionAttributeSource()));
        return (DrivineChunkHistoryStore) factory.getProxy();
    }

    @Test
    void e1_theStringFormRunsInATransaction() {
        proxiedStore().clearByContextValue("acme");

        assertEquals(List.of("begin", "commit"), transactions.events);
    }
}
