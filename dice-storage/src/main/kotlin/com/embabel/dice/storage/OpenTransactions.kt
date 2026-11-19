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

import org.drivine.manager.DelegatingPersistenceManager
import org.drivine.manager.PersistenceManager
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.support.TransactionTemplate

/**
 * Whether this manager has a transaction open on the calling thread that a statement would join.
 *
 * Spring's own "is a transaction active" answers yes for a transaction of any manager. A host can
 * bring a manager for its own database, and while its transaction is open nothing of Drivine's is:
 * each Drivine statement would commit on its own. Asking the store's own manager tells the two
 * apart.
 */
internal fun PlatformTransactionManager.hasOpenTransaction(): Boolean =
    TransactionTemplate(this).apply {
        propagationBehavior = TransactionDefinition.PROPAGATION_SUPPORTS
        // Read-only, so a check made inside a read-only transaction is not refused for asking.
        isReadOnly = true
    }.execute { status -> status.hasTransaction() } == true

/**
 * Whether a transaction is open on this thread that the store's statements run in: one this
 * manager knows of, or one begun through Drivine's own `Transactions`, which [persistenceManager]
 * keeps in its transaction state. When Drivine reads the host's data source through a
 * transaction-aware proxy, the store's manager is the host's and cannot see Drivine's own.
 */
internal fun PlatformTransactionManager.hasOpenTransaction(persistenceManager: PersistenceManager): Boolean =
    (persistenceManager as? DelegatingPersistenceManager)?.contextHolder?.currentTransaction != null ||
        hasOpenTransaction()
