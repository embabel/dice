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

import com.embabel.dice.proposition.Proposition;
import com.embabel.dice.proposition.PropositionQuery;
import com.embabel.dice.proposition.PropositionRepository;
import com.embabel.dice.proposition.PropositionStatus;
import example.dice.starter.customers.AccountId;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;

/**
 * What the support desk remembers about one account.
 *
 * <p>Every method takes the account and builds its query from it. A DICE query starts from a
 * context, so one account's facts cannot appear in another account's answer.
 */
@Service
public class CustomerMemory {

    private final PropositionRepository propositions;

    /**
     * @param propositions the store the facts are read from
     */
    CustomerMemory(PropositionRepository propositions) {
        this.propositions = propositions;
    }

    /**
     * Everything currently believed about an account, most confident first.
     *
     * @param account whose memory to read
     * @return the account's active facts
     */
    public List<Proposition> currentFacts(AccountId account) {
        return propositions.query(
                PropositionQuery.againstContext(account.value())
                        .withStatus(PropositionStatus.ACTIVE)
                        .orderedByEffectiveConfidence());
    }

    /**
     * The facts still worth trusting at some point in time.
     *
     * <p>Confidence fades as a fact ages, faster for facts with a high decay rate. Asking as of
     * a later date shows which facts will have gone stale by then.
     *
     * @param account       whose memory to read
     * @param asOf          the moment to judge confidence at
     * @param minConfidence the lowest confidence after decay to keep, from 0 to 1
     * @return the account's active facts that are still at or above {@code minConfidence}
     */
    public List<Proposition> trustedAsOf(AccountId account, Instant asOf, double minConfidence) {
        return propositions.query(
                PropositionQuery.againstContext(account.value())
                        .withStatus(PropositionStatus.ACTIVE)
                        .withEffectiveConfidenceAsOf(asOf)
                        .withMinEffectiveConfidence(minConfidence)
                        .orderedByEffectiveConfidence());
    }
}
