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
package com.embabel.dice.store

import com.embabel.dice.proposition.PropositionStore
import org.jetbrains.annotations.ApiStatus
import java.util.Collections
import java.util.EnumSet

/**
 * An optional thing a proposition store can do. Each one has an interface that states its rules.
 * A store that lists a capability implements that interface.
 *
 * EXPERIMENTAL. This API may change before 1.0.
 *
 * @property contract The interface a store implements when it lists this capability.
 */
@ApiStatus.Experimental
enum class StoreCapability(val contract: Class<*>) {

    /** Similarity search that filters before it picks the nearest K. */
    FILTERED_VECTOR_SEARCH(FilteredVectorSearch::class.java),

    /** Ranked word search over proposition text. */
    FULL_TEXT_SEARCH(FullTextSearch::class.java),

    /** Follows abstraction links to a depth in the database. */
    ABSTRACTION_TRAVERSAL(AbstractionTraversal::class.java),

    /** Commits several writes together or none of them. */
    ATOMIC_WRITES(AtomicWrites::class.java),
}

/**
 * When a store's similarity search applies a filter, relative to picking the nearest K.
 *
 * With [AFTER_TOP_K], a search for the five closest propositions about one customer picks the
 * five closest overall and then drops the ones about other customers, which can leave none.
 *
 * EXPERIMENTAL. This API may change before 1.0.
 */
@ApiStatus.Experimental
enum class VectorFiltering {

    /**
     * The filter selects the candidates, and the nearest K of those are returned. A store has
     * this mode exactly when it lists [StoreCapability.FILTERED_VECTOR_SEARCH].
     */
    BEFORE_TOP_K,

    /**
     * The nearest K are picked and the filter then drops some of them. A filtered search can
     * return fewer than K, or nothing, when K matching propositions exist. This describes the
     * filtered search of `VectorSearchCapable` on a store that does not filter first.
     */
    AFTER_TOP_K,

    /** The store has no similarity search. */
    NONE,

    /** The store has not published its capabilities, so the mode is unknown. */
    UNDECLARED,
}

/**
 * What a store can do. A store publishes one, and a caller reads it before using a capability.
 *
 * A caller gets a capability with [find], which checks the descriptor and returns the store as
 * the capability's interface, or null.
 *
 * ```java
 * FullTextSearch search = StoreCapabilities.find(store, FullTextSearch.class);
 * if (search != null) {
 *     List<Proposition> matches = search.findMatching(request);
 * }
 * VectorFiltering filtering = StoreCapabilities.describe(store).getVectorFiltering();
 * ```
 *
 * ```kotlin
 * val search = StoreCapabilities.find<FullTextSearch>(store)
 * ```
 *
 * A store author builds one with a chain in Java or a block in Kotlin. Both give the same value.
 *
 * ```java
 * StoreCapabilities capabilities = StoreCapabilities
 *     .of(StoreCapability.FILTERED_VECTOR_SEARCH, StoreCapability.ATOMIC_WRITES);
 * ```
 *
 * ```kotlin
 * val capabilities = StoreCapabilities {
 *     add(StoreCapability.FILTERED_VECTOR_SEARCH, StoreCapability.ATOMIC_WRITES)
 * }
 * ```
 *
 * The descriptor states what one store instance can do with the collaborators it was built
 * with. A store built with no embedder leaves [StoreCapability.FILTERED_VECTOR_SEARCH] out.
 *
 * EXPERIMENTAL. This API may change before 1.0.
 *
 * @property vectorFiltering When the store's similarity search applies a filter. It is
 *   [VectorFiltering.BEFORE_TOP_K] exactly when [StoreCapability.FILTERED_VECTOR_SEARCH] is
 *   supported.
 */
@ApiStatus.Experimental
class StoreCapabilities private constructor(
    supported: Set<StoreCapability>,
    val vectorFiltering: VectorFiltering,
) {

    /** The capabilities this store has. Read-only. */
    val supported: Set<StoreCapability> =
        Collections.unmodifiableSet(EnumSet.noneOf(StoreCapability::class.java).apply { addAll(supported) })

    init {
        val filtersFirst = StoreCapability.FILTERED_VECTOR_SEARCH in this.supported
        require(filtersFirst == (vectorFiltering == VectorFiltering.BEFORE_TOP_K)) {
            "vectorFiltering is BEFORE_TOP_K exactly when FILTERED_VECTOR_SEARCH is supported, " +
                "but was $vectorFiltering with ${this.supported}"
        }
    }

    /**
     * @param capability The capability to ask about.
     * @return Whether this store has it.
     */
    fun supports(capability: StoreCapability): Boolean = capability in supported

    /**
     * @param capability The capability to add.
     * @return A descriptor that also supports [capability]. Adding
     *   [StoreCapability.FILTERED_VECTOR_SEARCH] sets [VectorFiltering.BEFORE_TOP_K].
     */
    fun with(capability: StoreCapability): StoreCapabilities =
        if (capability == StoreCapability.FILTERED_VECTOR_SEARCH) {
            StoreCapabilities(supported + capability, VectorFiltering.BEFORE_TOP_K)
        } else {
            StoreCapabilities(supported + capability, vectorFiltering)
        }

    /**
     * @param capability The capability to remove.
     * @return A descriptor that does not support [capability]. Removing
     *   [StoreCapability.FILTERED_VECTOR_SEARCH] sets [VectorFiltering.NONE].
     */
    fun without(capability: StoreCapability): StoreCapabilities =
        if (capability == StoreCapability.FILTERED_VECTOR_SEARCH && supports(capability)) {
            StoreCapabilities(supported - capability, VectorFiltering.NONE)
        } else {
            StoreCapabilities(supported - capability, vectorFiltering)
        }

    /**
     * Narrows a descriptor for a store that has no similarity search.
     *
     * @return A descriptor that does not support [StoreCapability.FILTERED_VECTOR_SEARCH] and
     *   whose filtering mode is [VectorFiltering.NONE].
     */
    fun withoutSimilaritySearch(): StoreCapabilities =
        StoreCapabilities(supported - StoreCapability.FILTERED_VECTOR_SEARCH, VectorFiltering.NONE)

    /**
     * States the filtering mode of a store that does not filter first.
     *
     * @param vectorFiltering [VectorFiltering.AFTER_TOP_K], [VectorFiltering.NONE] or
     *   [VectorFiltering.UNDECLARED].
     * @return A descriptor with this filtering mode.
     * @throws IllegalArgumentException when the mode does not agree with whether
     *   [StoreCapability.FILTERED_VECTOR_SEARCH] is supported.
     */
    fun withVectorFiltering(vectorFiltering: VectorFiltering): StoreCapabilities =
        StoreCapabilities(supported, vectorFiltering)

    override fun equals(other: Any?): Boolean =
        other is StoreCapabilities && other.supported == supported && other.vectorFiltering == vectorFiltering

    override fun hashCode(): Int = 31 * supported.hashCode() + vectorFiltering.hashCode()

    override fun toString(): String = "StoreCapabilities(supported=$supported, vectorFiltering=$vectorFiltering)"

    companion object {

        /** A store with no optional capability and no similarity search. */
        @JvmField
        val NONE: StoreCapabilities = StoreCapabilities(emptySet(), VectorFiltering.NONE)

        /** A store that has not published its capabilities. */
        @JvmField
        val UNDECLARED: StoreCapabilities = StoreCapabilities(emptySet(), VectorFiltering.UNDECLARED)

        /**
         * @param capabilities The capabilities the store has.
         * @return A descriptor that supports them. Its filtering mode is
         *   [VectorFiltering.BEFORE_TOP_K] when they include
         *   [StoreCapability.FILTERED_VECTOR_SEARCH], and [VectorFiltering.NONE] otherwise.
         */
        @JvmStatic
        fun of(vararg capabilities: StoreCapability): StoreCapabilities {
            val supported = capabilities.toSet()
            val filtering =
                if (StoreCapability.FILTERED_VECTOR_SEARCH in supported) VectorFiltering.BEFORE_TOP_K else VectorFiltering.NONE
            return StoreCapabilities(supported, filtering)
        }

        /**
         * Builds a descriptor in a block. Java uses [of] and the `with` chain.
         *
         * @param block Adds the capabilities and can set the filtering mode.
         * @return The descriptor.
         * @throws IllegalArgumentException when the filtering mode the block sets does not agree
         *   with the capabilities it adds.
         */
        @JvmSynthetic
        operator fun invoke(block: StoreCapabilitiesBuilder.() -> Unit): StoreCapabilities =
            StoreCapabilitiesBuilder().apply(block).build()

        /**
         * What [store] can do. Works on any proposition store.
         *
         * @param store The store to describe.
         * @return The store's own descriptor when it implements [CapabilityReporting], and
         *   [UNDECLARED] otherwise.
         */
        @JvmStatic
        fun describe(store: PropositionStore): StoreCapabilities =
            if (store is CapabilityReporting) store.capabilities else UNDECLARED

        /**
         * Returns [store] as a capability interface when the store supports that capability.
         *
         * The check is on [store] itself. A store that wraps another store passes a capability
         * through by implementing the interface and listing the capability.
         *
         * @param store The store to ask.
         * @param contract A capability interface, the `contract` of a [StoreCapability].
         * @return The store as [contract], or null when the store does not list the capability or
         *   does not implement the interface.
         * @throws IllegalArgumentException when [contract] is not a capability interface.
         */
        @JvmStatic
        fun <T : Any> find(store: PropositionStore, contract: Class<T>): T? {
            val capability = StoreCapability.values().firstOrNull { it.contract == contract }
            requireNotNull(capability) { "${contract.name} is not a store capability interface" }
            return if (describe(store).supports(capability) && contract.isInstance(store)) contract.cast(store) else null
        }

        /**
         * Returns [store] as the capability interface [T] when the store supports that
         * capability. Java uses the overload that takes a class.
         *
         * @param store The store to ask.
         * @return The store as [T], or null.
         * @throws IllegalArgumentException when [T] is not a capability interface.
         */
        @JvmSynthetic
        inline fun <reified T : Any> find(store: PropositionStore): T? = find(store, T::class.java)
    }
}

/**
 * Implemented by a store that publishes what it can do. Callers read it through
 * [StoreCapabilities.describe] and [StoreCapabilities.find].
 *
 * EXPERIMENTAL. This API may change before 1.0.
 */
@ApiStatus.Experimental
interface CapabilityReporting {

    /** What this store instance can do with the collaborators it was built with. */
    val capabilities: StoreCapabilities
}

/**
 * Collects capabilities while a `StoreCapabilities { }` block runs. It exists only inside the
 * block, and the block returns an immutable [StoreCapabilities].
 *
 * EXPERIMENTAL. This API may change before 1.0.
 */
@StoreContractDsl
@ApiStatus.Experimental
class StoreCapabilitiesBuilder internal constructor() {

    private val supported = EnumSet.noneOf(StoreCapability::class.java)

    /**
     * The filtering mode of a store that does not filter first. Leave it null to take the mode
     * that follows from the capabilities added.
     */
    var vectorFiltering: VectorFiltering? = null

    /**
     * Adds capabilities the store has.
     *
     * @param capabilities The capabilities to add.
     */
    fun add(vararg capabilities: StoreCapability) {
        supported.addAll(capabilities)
    }

    /**
     * Builds the descriptor the block described.
     *
     * @return The immutable descriptor.
     */
    internal fun build(): StoreCapabilities {
        val described = StoreCapabilities.of(*supported.toTypedArray())
        return vectorFiltering?.let(described::withVectorFiltering) ?: described
    }
}
