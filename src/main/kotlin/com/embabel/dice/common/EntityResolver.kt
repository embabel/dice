package com.embabel.dice.common

import com.embabel.agent.core.DataDictionary
import com.embabel.agent.rag.model.NamedEntityData
import com.embabel.agent.rag.model.SimpleNamedEntityData
import java.time.Instant

sealed interface SuggestedEntityResolution : Resolution<SuggestedEntity, NamedEntityData>

/**
 * No entity existed. We simply create a new entity.
 */
data class NewEntity(
    override val suggested: SuggestedEntity,
) : SuggestedEntityResolution, DiceEvent {

    override val timestamp: Instant = Instant.now()

    override val existing = null

    override val recommended: NamedEntityData = suggested.suggestedEntity

    override fun infoString(verbose: Boolean?, indent: Int): String {
        return "NewEntity(${recommended.infoString(verbose)})"
    }
}

/**
 * An existing entity was found that matches the suggested entity.
 * The recommended entity merges labels from both entities, preferring
 * the more specific type (e.g., Detective over Person).
 */
data class ExistingEntity(
    override val suggested: SuggestedEntity,
    override val existing: NamedEntityData,
) : SuggestedEntityResolution {

    /**
     * Merge labels from suggested and existing entities.
     * This ensures that if a Person is later identified as a Detective,
     * the merged entity has both labels.
     * Labels are normalized to simple names (no FQN).
     */
    override val recommended: NamedEntityData = SimpleNamedEntityData(
        id = existing.id,
        name = existing.name,
        description = existing.description ?: suggested.summary,
        labels = (existing.labels() + suggested.labels).map { it.substringAfterLast('.') }.toSet(),
        properties = existing.properties + suggested.properties,
    )

    override fun infoString(verbose: Boolean?, indent: Int): String {
        return "ExistingEntity(${existing.infoString(verbose)})"
    }
}

/**
 * We do not want to progress with this suggested entity.
 */
data class VetoedEntity(
    override val suggested: SuggestedEntity,
) : SuggestedEntityResolution {

    override val recommended = null

    override val existing = null

    override fun infoString(verbose: Boolean?, indent: Int): String {
        return "VetoedEntity(${suggested})"
    }
}

/**
 * Resolves entities based on existing data
 */
interface EntityResolver {

    /**
     * For each of the given entities, resolve them to either an existing entity or a new entity.
     */
    fun resolve(
        suggestedEntities: SuggestedEntities,
        schema: DataDictionary,
    ): com.embabel.dice.text2graph.Resolutions<SuggestedEntityResolution>

}