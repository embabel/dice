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
package com.embabel.dice.common

import com.embabel.agent.rag.model.NamedEntity
import com.embabel.chat.Message
import com.embabel.dice.incremental.IncrementalSource
import com.embabel.dice.proposition.extraction.ExtractionContentProfileRef
import com.embabel.dice.proposition.extraction.ExtractionRunRef
import com.embabel.dice.provenance.SourceLocator
import com.embabel.dice.provenance.SourceRevisionRef
import org.springframework.context.ApplicationEvent

/**
 * Base event for requesting proposition extraction from any incremental source.
 * The [user] is typed as [NamedEntity] so that any application's user type
 * (e.g., UrbotUser, Customer) can be used directly.
 *
 * A publisher that knows where its material came from can say so by overriding
 * [sourceLocator] and [sourceRevision]. The extraction listener collects both into an
 * `ExtractionRequest` and puts them onto the `SourceAnalysisContext` it builds, so the async path
 * grounds propositions exactly the way a direct `rememberText` call carrying a request does. Both
 * default to null, so an existing subclass carries no provenance and behaves as it always did.
 *
 * [profile] and [currentRun] work the same way and reach the same context through the same
 * call, so an async publisher can attribute its extraction to a content profile and a run
 * without the listener growing a second code path. Both also default to null.
 */
abstract class SourceAnalysisRequestEvent(
    source: Any,
    @JvmField val user: NamedEntity,
) : ApplicationEvent(source) {

    abstract fun incrementalSource(): IncrementalSource<Message>

    /**
     * Where this event's material lives, when the publisher knows.
     */
    open fun sourceLocator(): SourceLocator? = null

    /**
     * The revision of [sourceLocator] this event's material was read at, when the publisher
     * knows. Its source key must match the locator's, and the listener checks that when it
     * builds the context.
     */
    open fun sourceRevision(): SourceRevisionRef? = null

    /**
     * The extraction content profile this event's analysis should be attributed to, when the
     * publisher has one. EXPERIMENTAL. DICE carries it and routes nothing on it.
     */
    open fun profile(): ExtractionContentProfileRef? = null

    /**
     * The extraction run this event's analysis belongs to, when the publisher is running one.
     * EXPERIMENTAL. Identity only — nothing is stored under it until DICE #67 lands.
     */
    open fun currentRun(): ExtractionRunRef? = null
}
