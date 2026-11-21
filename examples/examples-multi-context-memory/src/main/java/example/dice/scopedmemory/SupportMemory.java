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
package example.dice.scopedmemory;

import com.embabel.dice.agent.ScopedMemory;
import com.embabel.dice.proposition.store.InMemoryPropositionRepository;
import com.embabel.dice.store.ContextReadScope;
import com.embabel.dice.store.ReadBudget;
import com.embabel.dice.store.ReadCriteria;
import com.embabel.dice.store.ReadQuery;
import com.embabel.dice.store.ScopedReadSpec;

import java.util.List;
import java.util.Map;

final class SupportMemory {

    record AuthenticatedPrincipal(String tenantId, String principalId) {}

    record Grant(List<String> contextIds, int maxRows) {
        Grant {
            contextIds = List.copyOf(contextIds);
        }
    }

    private final InMemoryPropositionRepository repository;
    private final Map<AuthenticatedPrincipal, Grant> grants;

    /** The host supplies the grants from its authorization policy. */
    SupportMemory(InMemoryPropositionRepository repository, Map<AuthenticatedPrincipal, Grant> grants) {
        this.repository = repository;
        this.grants = Map.copyOf(grants);
    }

    /** Takes the authenticated identity from the host's request context. */
    ScopedMemory forPrincipal(AuthenticatedPrincipal principal) {
        var grant = grants.get(principal);
        if (grant == null) {
            throw new IllegalArgumentException("No memory grant for the authenticated principal.");
        }
        var spec = ScopedReadSpec.of(ContextReadScope.ofValues(grant.contextIds()));
        var budget = ReadBudget.ofRows(grant.maxRows());
        var recent = ReadQuery.of(ReadCriteria.empty()).orderedByCreated().withLimit(Math.min(3, grant.maxRows()));
        return ScopedMemory.forReads(repository.scopedReads(), spec, budget).withEagerQuery(recent);
    }
}
