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
package com.embabel.dice.spi

import com.embabel.dice.proposition.Proposition
import com.embabel.dice.provenance.ConnectorRef
import com.embabel.dice.provenance.ContentAddressedLocator
import com.embabel.dice.provenance.FileLocator
import com.embabel.dice.provenance.SourceLocator
import com.embabel.dice.provenance.UriLocator
import org.slf4j.LoggerFactory
import kotlin.reflect.KClass

/**
 * How authoritative the source backing a proposition is.
 *
 * Tiers are declared in descending authority, so the enum `ordinal` is a built-in
 * ranking: a *lower* ordinal means *higher* authority. [PRIMARY] (ordinal 0) is the
 * most authoritative; [UNKNOWN] (the highest ordinal) is the least and is the fail-safe
 * default when authority cannot be determined.
 */
enum class AuthorityTier {
    /** First-party / directly attributable source (e.g. a connector reference). */
    PRIMARY,

    /** Named external source such as a URI or file. */
    SECONDARY,

    /** Indirect or derived source (e.g. content-addressed material, abstractions). */
    DERIVED,

    /** Authority could not be determined; the lowest-authority fail-safe default. */
    UNKNOWN,
}

/**
 * Policy SPI: resolves the [AuthorityTier] of a proposition's source.
 *
 * Authority is derived from how a proposition is grounded, not from its content.
 * Consumers may supply their own implementation; DICE ships a constant resolver
 * ([FixedAuthorityResolver]) and a grounding-driven one ([StructuralAuthorityResolver]).
 */
interface AuthorityResolver {

    /**
     * Resolve the authority tier for a proposition.
     *
     * @param proposition The proposition whose source authority to resolve
     * @return the resolved [AuthorityTier]
     */
    fun resolve(proposition: Proposition): AuthorityTier
}

/**
 * [AuthorityResolver] that always returns a single configured [tier], ignoring the
 * proposition entirely. Useful when every proposition in a context shares the same
 * provenance, or as a deterministic stand-in.
 *
 * @property tier The fixed tier returned for every proposition (defaults to [AuthorityTier.UNKNOWN])
 */
class FixedAuthorityResolver @JvmOverloads constructor(
    private val tier: AuthorityTier = AuthorityTier.UNKNOWN,
) : AuthorityResolver {

    override fun resolve(proposition: Proposition): AuthorityTier = tier
}

/**
 * [AuthorityResolver] that maps a proposition's grounding locator kinds to authority
 * tiers via [tierByLocatorKind].
 *
 * The proposition's [Proposition.provenanceEntries] locators are inspected and the
 * *strongest* (lowest-ordinal) mapped tier across them is returned. A proposition with
 * no grounding fails safe to [AuthorityTier.UNKNOWN].
 *
 * @property tierByLocatorKind Mapping from [SourceLocator] subtype to [AuthorityTier];
 *   defaults to [DEFAULT_MAP]
 */
class StructuralAuthorityResolver @JvmOverloads constructor(
    private val tierByLocatorKind: Map<KClass<out SourceLocator>, AuthorityTier> = DEFAULT_MAP,
) : AuthorityResolver {

    private val logger = LoggerFactory.getLogger(StructuralAuthorityResolver::class.java)

    override fun resolve(proposition: Proposition): AuthorityTier {
        val locators = proposition.provenanceEntries.map { it.locator }
        if (locators.isEmpty()) {
            logger.debug("No grounding locators on proposition {}; authority falls back to UNKNOWN", proposition.id.take(8))
            return AuthorityTier.UNKNOWN
        }
        // Strongest authority == lowest ordinal across all grounding locators.
        val tier = locators
            .map { tierByLocatorKind[it::class] ?: AuthorityTier.UNKNOWN }
            .minByOrNull { it.ordinal }
            ?: AuthorityTier.UNKNOWN
        logger.debug(
            "Resolved authority {} for proposition {} from {} locator kind(s): {}",
            tier, proposition.id.take(8), locators.size, locators.map { it::class.simpleName },
        )
        return tier
    }

    companion object {
        /**
         * Default locator-kind → tier mapping: connector references are first-party
         * ([AuthorityTier.PRIMARY]); URIs and files are named external sources
         * ([AuthorityTier.SECONDARY]); content-addressed material is indirect
         * ([AuthorityTier.DERIVED]).
         */
        @JvmField
        val DEFAULT_MAP: Map<KClass<out SourceLocator>, AuthorityTier> = mapOf(
            ConnectorRef::class to AuthorityTier.PRIMARY,
            UriLocator::class to AuthorityTier.SECONDARY,
            FileLocator::class to AuthorityTier.SECONDARY,
            ContentAddressedLocator::class to AuthorityTier.DERIVED,
        )
    }
}
