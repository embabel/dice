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
package example.dice.starter.memory;

import com.embabel.agent.core.DataDictionary;
import com.embabel.agent.rag.service.NamedEntityDataRepository;
import com.embabel.agent.rag.service.support.InMemoryNamedEntityDataRepository;
import example.dice.starter.customers.AccountId;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The entities that tickets mention, kept in one store per account.
 *
 * <p>Entity resolution searches the store it is given. Given one account's store, a name in
 * that account's ticket can only match an entity the same account created.
 */
@Component
class AccountEntities {

    private final DataDictionary schema;
    private final Map<AccountId, NamedEntityDataRepository> stores = new ConcurrentHashMap<>();

    /**
     * @param schema the schema each store reads entity types from
     */
    AccountEntities(@Qualifier("supportSchema") DataDictionary schema) {
        this.schema = schema;
    }

    /**
     * The entity store of one account. It is created empty the first time the account is seen.
     *
     * @param account whose entities to return
     * @return the account's store
     */
    NamedEntityDataRepository of(AccountId account) {
        return stores.computeIfAbsent(account, unused -> new InMemoryNamedEntityDataRepository(schema));
    }
}
