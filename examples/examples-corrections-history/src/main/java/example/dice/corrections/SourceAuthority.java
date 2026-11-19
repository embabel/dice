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
package example.dice.corrections;

import com.embabel.dice.proposition.Proposition;
import com.embabel.dice.provenance.ConnectorRef;
import com.embabel.dice.provenance.SourceLocator;
import com.embabel.dice.spi.AuthorityResolver;
import com.embabel.dice.spi.AuthorityTier;

import java.util.Comparator;

/**
 * Decides how far to trust a fact from where it came from. The billing system is the record
 * of what a customer pays for, so a fact from billing is {@code PRIMARY}. Support chat is
 * someone's account of it, so a fact from chat is {@code DERIVED}. Anything else is
 * {@code UNKNOWN}.
 *
 * <p>A fact can carry several sources, because a repeat or a reinforcement adds the new
 * message's source to the stored fact. The strongest source wins, the same way DICE's own
 * {@code StructuralAuthorityResolver} does it, so a chat fact that billing later confirms is
 * trusted as billing.
 */
final class SourceAuthority implements AuthorityResolver {

    /** The connector id of the billing system. */
    static final String BILLING = "billing";

    /** The connector id of support chat. */
    static final String CHAT = "chat";

    /**
     * @param proposition the fact to judge
     * @return the strongest tier among its sources, or {@code UNKNOWN} when it has none
     */
    @Override
    public AuthorityTier resolve(Proposition proposition) {
        return proposition.getProvenanceEntries().stream()
                .map(entry -> tierOf(entry.getLocator()))
                .min(Comparator.comparingInt(AuthorityTier::ordinal))
                .orElse(AuthorityTier.UNKNOWN);
    }

    /**
     * @param locator one source of a fact
     * @return how far that source is trusted
     */
    private static AuthorityTier tierOf(SourceLocator locator) {
        if (!(locator instanceof ConnectorRef connector)) {
            return AuthorityTier.UNKNOWN;
        }
        return switch (connector.getConnectorId()) {
            case BILLING -> AuthorityTier.PRIMARY;
            case CHAT -> AuthorityTier.DERIVED;
            default -> AuthorityTier.UNKNOWN;
        };
    }
}
