package com.embabel.dice.common.resolver.matcher

import com.embabel.agent.core.DataDictionary
import com.embabel.agent.core.DomainType
import com.embabel.agent.rag.model.NamedEntityData
import com.embabel.dice.common.SuggestedEntity
import com.embabel.dice.common.resolver.EntityMatchingStrategy
import com.embabel.dice.common.resolver.MatchResult

/**
 * Checks label compatibility including type hierarchy.
 * Returns NoMatch if labels are incompatible, Inconclusive otherwise.
 *
 * Labels are compatible if:
 * 1. They share at least one label in common (case-insensitive), OR
 * 2. One type is a subtype of the other in the schema hierarchy, OR
 * 3. Both types share a common parent (e.g., Doctor and Detective both extend Person)
 */
class LabelCompatibilityStrategy : EntityMatchingStrategy {

    override fun evaluate(
        suggested: SuggestedEntity,
        candidate: NamedEntityData,
        schema: DataDictionary,
    ): MatchResult {
        val suggestedLabels = suggested.labels.toSet()
        val candidateLabels = candidate.labels()

        if (!labelsCompatible(suggestedLabels, candidateLabels, schema)) {
            return MatchResult.NoMatch
        }
        return MatchResult.Inconclusive
    }

    private fun labelsCompatible(labels1: Set<String>, labels2: Set<String>, schema: DataDictionary): Boolean {
        // Normalize labels to simple names (handles fully qualified like com.example.Person)
        // Filter out framework labels that shouldn't affect compatibility
        val frameworkLabels = setOf(NamedEntityData.ENTITY_LABEL, "Entity", "Reference")
        val simple1 = labels1.map { it.substringAfterLast('.') }.filter { it !in frameworkLabels }.toSet()
        val simple2 = labels2.map { it.substringAfterLast('.') }.filter { it !in frameworkLabels }.toSet()

        // Direct label match (case-insensitive) - try both original and simple names
        if (labels1.any { l1 -> labels2.any { l2 -> l1.equals(l2, ignoreCase = true) } }) {
            return true
        }
        if (simple1.any { l1 -> simple2.any { l2 -> l1.equals(l2, ignoreCase = true) } }) {
            return true
        }

        // Check type hierarchy using schema
        val type1 = schema.domainTypeForLabels(simple1)
        val type2 = schema.domainTypeForLabels(simple2)

        if (type1 != null && type2 != null) {
            if (isSubtypeOf(type1, type2) || isSubtypeOf(type2, type1)) {
                return true
            }
            if (shareCommonParent(type1, type2)) {
                return true
            }
        }

        return false
    }

    private fun isSubtypeOf(type1: DomainType, type2: DomainType): Boolean {
        for (parent in type1.parents) {
            if (parent.name.equals(type2.name, ignoreCase = true)) {
                return true
            }
            if (isSubtypeOf(parent, type2)) {
                return true
            }
        }
        return false
    }

    private fun shareCommonParent(type1: DomainType, type2: DomainType): Boolean {
        val parents1 = getAllParents(type1)
        val parents2 = getAllParents(type2)
        // Filter out generic parents that are too broad for meaningful compatibility
        val genericParents = setOf(
            "NamedEntity", "Entity", "RetrievableEntity", "Retrievable",
            "Object", "Any", "Embeddable", "EntityData", "NamedEntityData"
        )
        val meaningful1 = parents1.filter { it.name !in genericParents }
        val meaningful2 = parents2.filter { it.name !in genericParents }
        return meaningful1.any { p1 -> meaningful2.any { p2 -> p1.name.equals(p2.name, ignoreCase = true) } }
    }

    private fun getAllParents(type: DomainType): Set<DomainType> {
        val parents = mutableSetOf<DomainType>()
        for (parent in type.parents) {
            parents.add(parent)
            parents.addAll(getAllParents(parent))
        }
        return parents
    }
}