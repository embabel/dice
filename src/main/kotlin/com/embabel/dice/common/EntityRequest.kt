package com.embabel.dice.common

/**
 * Identifier for an entity lookup.
 * Ensures that ids don't need to be globally unique by namespacing them with a type.
 *
 * @param id The unique identifier of the entity within its type.
 * @param type The type or namespace of the entity. This may be only one of multiple labels.
 */
data class EntityRequest(
    val id: String,
    val type: String,
) {

    companion object {

        fun forUser(id: String) =
            EntityRequest(id = id, type = "User")
    }
}