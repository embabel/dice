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

/**
 * The name the graph stores look their transaction manager up by.
 *
 * Each store is qualified with this name, so its `@Transactional` methods run in the transaction
 * manager registered under it, and in the context's default manager when there is none. DICE's
 * auto-configuration registers one: Drivine's manager, or the host's own where Drivine reads the
 * host's data source through a transaction-aware proxy. It is never offered for injection by type,
 * so a host's own `@Transactional` code keeps the host's default manager. A host that wires the
 * stores itself, alongside a manager of its own for another database, registers Drivine's manager
 * under this name to get the same.
 */
object DiceStorageTransactions {

    /** The bean name of the graph stores' transaction manager. */
    const val MANAGER = "diceTransactionManager"
}
