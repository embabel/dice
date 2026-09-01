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
package com.embabel.dice.storage

import com.embabel.dice.storage.model.Mention
import com.embabel.dice.storage.model.ProcessedChunkNode
import com.embabel.dice.storage.model.PropositionNode
import com.embabel.dice.storage.model.SourceNode
import org.drivine.annotation.NodeFragment
import org.drivine.annotation.RelationshipFragment
import org.drivine.schema.SchemaItemSpec
import org.drivine.schema.UniquenessConstraintSpec
import kotlin.reflect.KClass
import kotlin.reflect.KType
import kotlin.reflect.full.hasAnnotation
import kotlin.reflect.full.isSubclassOf
import kotlin.reflect.full.primaryConstructor

/**
 * What a node in a graph has to look like for dice to own it: the labels dice's own storage writes,
 * and, per label, the properties dice writes on every such node.
 *
 * ## Why ownership needs saying at all
 *
 * A drift check compares the domain schema an app declared against what a live graph holds, and it
 * shares that graph with dice's own storage. Nothing dice writes for itself belongs to a declared
 * domain schema, so counting those nodes would report dice's bookkeeping as drift on every run, and
 * would make governance report itself: stamping a version and writing a drift report both add nodes
 * to the graph the next check observes. `DrivineObservedSchemaSource` subtracts what this object
 * describes.
 *
 * Names alone can't decide it. An app is free to govern its own type called `Source`, and hiding
 * every `(:Source)` node would hide that type from every report it should appear in. So ownership is
 * decided by shape: a label counts as dice's only while every node wearing it carries the properties
 * dice's own writer always writes.
 *
 * ## The shapes come from the schema definitions
 *
 * Nothing here is a hand-kept list of property names. Each shape is read out of the definition the
 * store already writes from, so the two cannot disagree:
 *
 * - **Node fragments** ([PropositionNode], [Mention], [SourceNode], [ProcessedChunkNode]) carry
 *   their label in `@NodeFragment` and their properties as constructor parameters. The shape is
 *   every parameter dice's writer cannot leave out: declared non-null, with no default value to fall
 *   back on. Optional and nullable ones stay out, because a node dice wrote is allowed to be missing
 *   them, and demanding one would make dice's own nodes look foreign and bring back the
 *   self-reporting case above.
 * - **Cypher-backed stores** ([MetamodelSchema], [CollectorTraceSchema], [LineageSchema]) declare
 *   their labels and natural keys as uniqueness constraints. The shape is the union of the key
 *   properties for that label, which is exactly what those stores MERGE on, so every node they
 *   create carries all of them.
 *
 * Adding a node label to any of those definitions carries its shape here with it. A new node
 * fragment is the one case needing a line: add its class to [NODE_FRAGMENTS].
 *
 * ## Where the boundary genuinely blurs
 *
 * A shape is a claim about properties, so a domain node carrying all of dice's properties for a
 * label it shares is indistinguishable from dice's own. A host that keeps `(:Source {key, kind})`
 * nodes of its own meaning has built dice's exact shape, and that type stays out of whole-graph
 * observation until dice writes an ownership marker at persistence time, which is a data migration
 * for existing graphs and a decision for a later change. Everything short of that is caught: a
 * domain `(:Source {key})` with no `kind` keeps `Source` observable, as does any other domain node
 * missing a property dice always writes. Context-scoped observation avoids the question entirely,
 * since it reads mention types and edges marked as projected from domain data.
 */
object DiceOwnedSchema {

    /**
     * The Drivine node fragments dice persists directly. Each one names its own label and carries
     * its own properties, so this list holds classes and no strings.
     */
    private val NODE_FRAGMENTS: List<KClass<*>> = listOf(
        PropositionNode::class,
        Mention::class,
        SourceNode::class,
        ProcessedChunkNode::class,
    )

    /**
     * Every node label dice writes, with the properties that identify a node carrying that label as
     * dice's own. A node missing any of them was written by somebody else.
     */
    val NODE_SHAPES: Map<String, List<String>> = buildMap {
        NODE_FRAGMENTS.forEach { fragment -> putAll(shapesOf(fragment)) }
        putAll(keyShapesOf(MetamodelSchema.specs()))
        putAll(keyShapesOf(CollectorTraceSchema.specs()))
        putAll(keyShapesOf(LineageSchema.specs()))
    }

    /** The label names, for callers that only need the names. */
    val LABELS: Set<String> = NODE_SHAPES.keys

    /**
     * A Cypher predicate that holds for a node dice owns: every property of the label's shape is
     * present.
     *
     * Label and property names are compile-time constants of the definitions above, so nothing
     * caller-derived is assembled into Cypher.
     *
     * @param alias The variable the node is bound to.
     * @param label One of [LABELS].
     * @return The predicate text, ready to follow a `WHERE`.
     */
    fun ownedNodePredicate(alias: String, label: String): String {
        val shape = requireNotNull(NODE_SHAPES[label]) { "'$label' is no dice storage label" }
        return shape.joinToString(" AND ") { property -> "$alias.$property IS NOT NULL" }
    }

    /** The label a fragment writes, mapped to the properties dice always writes on it. */
    private fun shapesOf(fragment: KClass<*>): Map<String, List<String>> {
        val labels = fragment.annotations.filterIsInstance<NodeFragment>().singleOrNull()?.labels
            ?: error("${fragment.simpleName} carries no @NodeFragment")
        val shape = alwaysWritten(fragment)
        return labels.associateWith { shape }
    }

    /**
     * The fragment's properties dice's writer cannot leave out: declared non-null, with no default
     * value, and held in the node as a single property.
     *
     * A default value is what tells us a property is optional. Drivine's `@Default` and
     * `@EmptyWhenAbsent` both go on parameters that carry one, so this covers them, along with
     * `@PropertyBag`, whose map is spread across `metadata.<key>` properties and appears under no
     * name of its own.
     */
    private fun alwaysWritten(fragment: KClass<*>): List<String> {
        val constructor = fragment.primaryConstructor
            ?: error("${fragment.simpleName} has no primary constructor")
        return constructor.parameters
            .filter { parameter -> !parameter.isOptional && !parameter.type.isMarkedNullable }
            .filter { parameter -> isSingleProperty(parameter.type) }
            .mapNotNull { parameter -> parameter.name }
    }

    /**
     * Whether a value of this type lands in the node as one property. Collections, maps and nested
     * fragments do something else with it, so they take no part in a shape.
     */
    private fun isSingleProperty(type: KType): Boolean {
        val classifier = type.classifier as? KClass<*> ?: return false
        if (classifier.isSubclassOf(Collection::class) || classifier.isSubclassOf(Map::class)) return false
        return !classifier.hasAnnotation<NodeFragment>() && !classifier.hasAnnotation<RelationshipFragment>()
    }

    /**
     * The labels a Cypher-backed store declares, each mapped to every property its uniqueness
     * constraints name. Those properties are the store's MERGE keys, so a node it created carries
     * all of them. Range indexes are left alone, since they cover properties a record can be
     * missing.
     */
    private fun keyShapesOf(specs: List<SchemaItemSpec>): Map<String, List<String>> =
        specs.filterIsInstance<UniquenessConstraintSpec>()
            .groupBy { spec -> spec.label }
            .mapValues { (_, group) -> group.flatMap { spec -> spec.properties }.distinct() }
}
