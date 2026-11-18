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

import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.support.AbstractPlatformTransactionManager
import org.springframework.transaction.support.DefaultTransactionStatus

/**
 * A transaction manager that knows nothing of Drivine, like one a host brings for a database of
 * its own. Its transactions hold nothing.
 */
internal class ForeignTransactionManager : AbstractPlatformTransactionManager() {
    override fun doGetTransaction(): Any = Any()
    override fun doBegin(transaction: Any, definition: TransactionDefinition) = Unit
    override fun doCommit(status: DefaultTransactionStatus) = Unit
    override fun doRollback(status: DefaultTransactionStatus) = Unit
}
