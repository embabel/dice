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
package com.embabel.dice.common.filter

import com.embabel.agent.core.DataDictionary
import com.embabel.agent.core.DomainType
import com.embabel.agent.core.ValidatedPropertyDefinition
import com.embabel.dice.proposition.SuggestedMention
import org.slf4j.LoggerFactory

/**
 * Schema-driven mention filter that uses DataDictionary validation rules.
 *
 * Looks up the entity type in the DataDictionary and applies validation rules
 * defined in the [ValidatedPropertyDefinition].
 *
 * Example type-safe schema definition:
 * ```kotlin
 * val companyType = DynamicType(
 *     name = "Company",
 *     ownProperties = listOf(
 *         ValidatedPropertyDefinition(
 *             name = "name",
 *             validationRules = listOf(
 *                 NoVagueReferences(),
 *                 LengthConstraint(maxLength = 150)
 *             )
 *         )
 *     )
 * )
 * ```
 *
 * @property dataDictionary The schema registry containing entity type definitions
 * @property propertyName The property name to validate (defaults to "name")
 */
class SchemaValidatedMentionFilter @JvmOverloads constructor(
    private val dataDictionary: DataDictionary,
    private val propertyName: String = "name"
) : MentionFilter {

    private val logger = LoggerFactory.getLogger(SchemaValidatedMentionFilter::class.java)

    override fun isValid(mention: SuggestedMention, propositionText: String): Boolean {
        // Look up the domain type for this entity type
        val domainType = findDomainType(mention.type)

        if (domainType == null) {
            // No schema found - allow by default
            logger.trace("No schema found for entity type '{}', allowing mention", mention.type)
            return true
        }

        // Find the name property - must be ValidatedPropertyDefinition for type-safe rules
        val nameProperty = domainType.properties.find { it.name == propertyName }

        if (nameProperty == null || nameProperty !is ValidatedPropertyDefinition) {
            // No validation rules defined
            logger.trace("No validated property '{}' found for type '{}', allowing mention", propertyName, mention.type)
            return true
        }

        // Validate the mention against the property's rules
        val valid = nameProperty.isValid(mention.span)

        if (!valid) {
            logger.debug(
                "Mention '{}' for type '{}' failed validation: {}",
                mention.span,
                mention.type,
                nameProperty.failureReason(mention.span)
            )
        }

        return valid
    }

    override fun rejectionReason(mention: SuggestedMention): String? {
        val domainType = findDomainType(mention.type) ?: return null
        val nameProperty = domainType.properties.find { it.name == propertyName } ?: return null

        if (nameProperty !is ValidatedPropertyDefinition) {
            return null
        }

        return nameProperty.failureReason(mention.span)
    }

    /**
     * Find domain type by matching entity type label.
     * Tries exact match first, then case-insensitive match.
     */
    private fun findDomainType(entityType: String): DomainType? {
        // Try exact label match
        val exactMatch = dataDictionary.domainTypeForLabels(setOf(entityType))
        if (exactMatch != null) {
            return exactMatch
        }

        // Try case-insensitive match
        val allTypes = dataDictionary.domainTypes
        return allTypes.find { domainType ->
            domainType.labels.any { label -> label.equals(entityType, ignoreCase = true) }
        }
    }
}
