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
package com.embabel.dice.bundle

/**
 * A snapshot of an entity that lives in the consuming application's own graph — dice never owns
 * these entities itself; it only ever sees the opaque [com.embabel.dice.proposition.EntityMention.resolvedId]
 * string on a mention. This is how a bundle can carry that entity data alongside the propositions
 * that reference it, so an importer can restore both together.
 *
 * @property id The resolvedId this snapshot corresponds to (the same string that appears on
 *   [com.embabel.dice.proposition.EntityMention.resolvedId]).
 * @property type A free-text label for what kind of entity this is (e.g. "Person", "Organization").
 *   Dice doesn't validate or interpret this — it's whatever the consumer's own type system uses.
 * @property properties Plain, JSON-friendly key/value pairs describing the entity. Free-text values
 *   are welcome here (including pipes, tabs, newlines, and quotes) — this travels through the same
 *   Jackson mapper as the rest of the bundle, so escaping is handled for you.
 */
data class EntitySnapshot(
    val id: String,
    val type: String,
    val properties: Map<String, Any?> = emptyMap(),
)

/**
 * Export-side SPI: given the resolvedIds a bundle's propositions mention, return their entity
 * snapshots. A single-method interface so a consumer can hand over a lambda over its own entity
 * store rather than write a class.
 */
fun interface EntitySnapshotExporter {
    fun snapshotsFor(resolvedIds: Set<String>): List<EntitySnapshot>
}

/**
 * Import-side SPI: load entity snapshots into the consuming application's own graph. Bundle import
 * calls this before propositions are saved, so that by the time a proposition's mentions land,
 * the entities they resolve to already exist.
 */
fun interface EntitySnapshotImporter {
    fun importSnapshots(snapshots: List<EntitySnapshot>)
}

/** Convenience combination of both directions, for callers that want to wire one object for both. */
interface EntitySnapshotPort : EntitySnapshotExporter, EntitySnapshotImporter

/**
 * Null-object default: no entities known on export, nothing done on import. This is what makes the
 * entities section genuinely optional — a consumer that doesn't own any entity data never has to
 * wire anything, and dice keeps working standalone with bare resolvedId strings on mentions.
 */
object NoOpEntitySnapshotPort : EntitySnapshotPort {
    override fun snapshotsFor(resolvedIds: Set<String>): List<EntitySnapshot> = emptyList()
    override fun importSnapshots(snapshots: List<EntitySnapshot>) = Unit
}
