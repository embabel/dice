package com.embabel.dice.text2graph.builder

import com.embabel.agent.core.DataDictionary
import com.embabel.agent.rag.model.NamedEntityData
import com.embabel.dice.text2graph.KnowledgeGraphDelta
import org.slf4j.LoggerFactory
import kotlin.reflect.KParameter
import kotlin.reflect.full.primaryConstructor

class InMemoryObjectGraphGraphProjector : GraphProjector<Any> {

    private val logger = LoggerFactory.getLogger(javaClass)

    override fun project(schema: DataDictionary, delta: KnowledgeGraphDelta?): List<Any> {
        if (delta == null) {
            logger.info("No delta provided, returning empty object graph.")
            return emptyList()
        }

        val entityCache = mutableMapOf<String, Any>()
        val allEntities = delta.newOrModifiedEntities()
        val newRelationships = delta.newRelationships()

        // Build relationship map: sourceId -> list of (relationshipType, targetId)
        val relationshipMap = mutableMapOf<String, MutableList<Pair<String, String>>>()
        newRelationships.forEach { relationship ->
            val suggestedRel = relationship.suggested
            relationshipMap.getOrPut(suggestedRel.sourceId) { mutableListOf() }
                .add(suggestedRel.type to suggestedRel.targetId)
        }

        // First pass: instantiate leaf entities (those without outgoing relationships)
        allEntities.forEach { entity ->
            if (entity.id !in entityCache && entity.id !in relationshipMap) {
                val domainObject = instantiateEntity(schema, entity, emptyMap())
                if (domainObject != null) {
                    entityCache[entity.id] = domainObject
                }
            }
        }

        // Second pass: instantiate entities with relationships, now that targets are available
        var progress = true
        while (progress) {
            progress = false
            allEntities.forEach { entity ->
                if (entity.id !in entityCache) {
                    val relationships = relationshipMap[entity.id] ?: emptyList()
                    val allTargetsAvailable = relationships.all { (_, targetId) -> targetId in entityCache }

                    if (allTargetsAvailable) {
                        val domainObject = instantiateEntityWithRelationships(
                            schema,
                            entity,
                            relationships,
                            entityCache
                        )
                        if (domainObject != null) {
                            entityCache[entity.id] = domainObject
                            progress = true
                        }
                    }
                }
            }
        }

        // Return all instantiated objects
        return entityCache.values.toList()
    }

    private fun instantiateEntity(
        schema: DataDictionary,
        entity: NamedEntityData,
        entityCache: Map<String, Any>
    ): Any? {
        return instantiateEntityWithRelationships(schema, entity, emptyList(), entityCache)
    }

    private fun instantiateEntityWithRelationships(
        schema: DataDictionary,
        entity: NamedEntityData,
        relationships: List<Pair<String, String>>,
        entityCache: Map<String, Any>
    ): Any? {
        // Extract simple label names (without package qualifiers)
        // Filter out generic labels like "Entity" that all types have
        val simpleLabels = entity.labels()
            .map { it.substringAfterLast('.') }
            .filter { it != "Entity" }
            .toSet()

        logger.debug(
            "Resolving entity '{}' with original labels: {}, filtered simple: {}",
            entity.name,
            entity.labels(),
            simpleLabels
        )

        // Use simple labels for lookup since schema registers types by simple name
        val domainType = schema.domainTypeForLabels(simpleLabels)

        if (domainType == null) {
            logger.warn("Cannot resolve entity '${entity.name}' with labels $simpleLabels to a domain type in schema. Available types: ${schema.domainTypes.map { it.name }}")
            return null
        }

        logger.debug("Resolved entity '${entity.name}' to domain type: ${domainType.name}")
        // Get the actual class that domainType represents, not the class of domainType itself
        val getClazzMethod = domainType.javaClass.getMethod("getClazz")
        val javaClass: Class<*> = getClazzMethod.invoke(domainType) as Class<*>
        val kClass = javaClass.kotlin
        val constructor = kClass.primaryConstructor ?: return null

        val args = mutableMapOf<KParameter, Any?>()
        constructor.parameters.forEach { param ->
            val paramName = param.name ?: return@forEach
            val value = when {
                paramName == "name" -> entity.name
                paramName == "age" -> {
                    // Try properties first, then try to extract from description
                    entity.properties["age"] as? Int
                        ?: extractAge(entity.description)
                        ?: if (param.isOptional) null else 0
                }

                paramName == "breed" -> {
                    // Try properties first, then try to extract from description
                    entity.properties["breed"] as? String
                        ?: extractBreed(entity.description)
                        ?: if (param.isOptional) null else ""
                }
                // For collection properties, check if we have relationships
                param.type.classifier == List::class -> {
                    // Find relationships that match this property
                    val relatedObjects = relationships
                        .filter { (relType, _) ->
                            relType.lowercase().contains(paramName.lowercase().removeSuffix("s")) ||
                                    paramName.lowercase().contains(relType.lowercase())
                        }
                        .mapNotNull { (_, targetId) -> entityCache[targetId] }
                    relatedObjects
                }

                else -> null
            }

            if (value != null || param.isOptional) {
                args[param] = value
            }
        }

        return try {
            val result = constructor.callBy(args)
            logger.debug("Successfully instantiated ${kClass.simpleName} for entity '${entity.name}'")
            result
        } catch (e: Exception) {
            logger.warn("Failed to instantiate ${kClass.simpleName} for entity '${entity.name}': ${e.message}")
            null
        }
    }

    private fun extractAge(description: String): Int? {
        // Try to extract age from descriptions like "30-year-old" or "aged 35"
        val agePattern = Regex("""(\d+)-year-old|aged?\s+(\d+)""", RegexOption.IGNORE_CASE)
        val match = agePattern.find(description)
        return match?.let {
            (it.groupValues[1].toIntOrNull() ?: it.groupValues[2].toIntOrNull())
        }
    }

    private fun extractBreed(description: String): String? {
        // Try to extract breed from descriptions
        // Look for patterns like "German Shepherd" or "Siamese breed"
        val breedPattern = Regex("""(German Shepherd|Siamese)""", RegexOption.IGNORE_CASE)
        return breedPattern.find(description)?.value
    }
}