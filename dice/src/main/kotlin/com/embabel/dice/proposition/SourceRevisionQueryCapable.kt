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
package com.embabel.dice.proposition

import com.embabel.agent.core.ContextId
import com.embabel.dice.provenance.SourceLocator
import com.embabel.dice.provenance.SourceRevisionRef

/**
 * Opt-in capability for asking which propositions were read from a source, and from which revision
 * of that source.
 *
 * These finders live outside [PropositionStore] and [PropositionRepository] deliberately. Answering
 * them takes provenance, and plenty of backends store evidence without projecting it on an ordinary
 * context read. A shared default body written over such a read would return an empty list on those
 * backends, and an empty list already has a meaning here: nothing in this context cites that source.
 * A store that cannot look would then be indistinguishable from one that looked and found nothing.
 *
 * Implementing this interface is the promise that the backend really can look. A caller holding a
 * plain [PropositionRepository] asks for the capability with an `as?` test and handles its absence
 * as its own case:
 *
 * ```kotlin
 * val revisionQueries = repository as? SourceRevisionQueryCapable
 *     ?: error("this backend cannot answer source-revision queries")
 * ```
 *
 * Each finder is a pair: a typed entry point taking a [ContextId], and a plain-String variant that
 * carries the work. The typed one forwards to the String one, matching `findByContextId ->
 * findByContextIdValue` on [PropositionStore]. Implementations live on the String variant, because
 * [ContextId] is a value class: the typed method's JVM name is mangled and a Java backend cannot
 * override it, so an implementation placed there would be invisible to Java callers. The String
 * variant has an ordinary JVM signature and sits on the path of every typed call.
 *
 * A store whose reads already carry every provenance entry can pick up all three String variants
 * from [ProvenanceScanningSourceRevisionQueries].
 */
interface SourceRevisionQueryCapable {

    /**
     * Whether this particular instance can actually answer these queries. Implementing the interface
     * is a type-level promise; this is the runtime truth. A decorator that forwards to a backend it
     * only discovers at construction time reports here whether the backend it got can answer. Callers
     * check this before trusting an empty result.
     */
    val supportsSourceRevisionQueries: Boolean get() = true

    /**
     * Find propositions in [contextId] with evidence from any revision of [sourceKey].
     *
     * Both revisioned and revisionless evidence matches when its locator key equals [sourceKey].
     */
    fun findBySourceKey(contextId: ContextId, sourceKey: String): List<Proposition> =
        findBySourceKey(contextId.value, sourceKey)

    /**
     * [findBySourceKey] by plain context-id string — the implementation point, and what Java callers
     * use.
     */
    fun findBySourceKey(contextIdValue: String, sourceKey: String): List<Proposition>

    /**
     * Find propositions in [contextId] with evidence from exactly [ref]'s source key and revision.
     */
    fun findBySourceRevision(contextId: ContextId, ref: SourceRevisionRef): List<Proposition> =
        findBySourceRevision(contextId.value, ref)

    /**
     * [findBySourceRevision] by plain context-id string — the implementation point, and what Java
     * callers use.
     */
    fun findBySourceRevision(contextIdValue: String, ref: SourceRevisionRef): List<Proposition>

    /**
     * Find propositions in [contextId] with revisionless evidence whose locator key equals
     * [locator]'s key.
     *
     * Evidence carrying any revision is excluded.
     */
    fun findRevisionlessBySourceLocator(
        contextId: ContextId,
        locator: SourceLocator,
    ): List<Proposition> =
        findRevisionlessBySourceLocator(contextId.value, locator)

    /**
     * [findRevisionlessBySourceLocator] by plain context-id string — the implementation point, and
     * what Java callers use.
     */
    fun findRevisionlessBySourceLocator(
        contextIdValue: String,
        locator: SourceLocator,
    ): List<Proposition>
}

/**
 * The three source-revision finders answered by reading the context and filtering loaded provenance
 * in memory.
 *
 * Only a store whose context read carries every provenance entry may implement this. That is the
 * condition the whole capability turns on: the in-memory and JSON-file stores keep entries on the
 * proposition itself, so scanning them is exact. A backend with a lean context read implements
 * [SourceRevisionQueryCapable] directly and pushes each predicate into its own query language.
 */
interface ProvenanceScanningSourceRevisionQueries : SourceRevisionQueryCapable, PropositionStore {

    override fun findBySourceKey(contextIdValue: String, sourceKey: String): List<Proposition> =
        findByContextId(ContextId(contextIdValue)).filter { proposition ->
            proposition.provenanceEntries.any { it.locator.key() == sourceKey }
        }

    override fun findBySourceRevision(contextIdValue: String, ref: SourceRevisionRef): List<Proposition> =
        findByContextId(ContextId(contextIdValue)).filter { proposition ->
            proposition.provenanceEntries.any {
                it.locator.key() == ref.sourceKey && it.sourceRevision == ref.sourceRevision
            }
        }

    override fun findRevisionlessBySourceLocator(
        contextIdValue: String,
        locator: SourceLocator,
    ): List<Proposition> =
        findByContextId(ContextId(contextIdValue)).filter { proposition ->
            proposition.provenanceEntries.any {
                it.locator.key() == locator.key() && it.sourceRevision == null
            }
        }
}
