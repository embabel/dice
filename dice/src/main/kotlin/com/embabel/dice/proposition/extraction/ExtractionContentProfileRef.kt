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
package com.embabel.dice.proposition.extraction

import org.jetbrains.annotations.ApiStatus

/**
 * Names a version of a host's extraction content profile.
 *
 * A profile is the host's durable answer to "what should extraction of this kind of material
 * do?" — the tone, the coverage, the house rules a product wants applied. DICE holds the
 * name and version and nothing else. It never looks the profile up, never reads policy out
 * of it, and never branches on it. The host owns the catalog, decides who may use which
 * profile, and binds it to whatever it actually means at call time.
 *
 * That split is the point. If DICE resolved profiles it would need a catalog, an
 * authorization model, and a connector for every host that has one. Instead a profile
 * reference is two opaque strings that ride along with the analysis so the run that produced
 * a claim can be attributed to the policy it ran under.
 *
 * **A profile selects no provider, no model, and no credential.** DICE routes nothing on it.
 * A host that wants a particular model for a particular profile makes that decision on its
 * own side, before it calls DICE.
 *
 * Profile is independent of every other dimension on
 * [com.embabel.dice.common.SourceAnalysisContext]. Perspective says whose statements to
 * mine out of conversational input; schema says what types exist; the context id says which
 * tenant owns the result. Setting one never constrains another — see
 * `docs/design/extraction-profiles.md`.
 *
 * Both strings are opaque to DICE: it compares them and carries them, and it parses neither.
 * Two profiles are the same profile when name and version both match, so a host that
 * republishes a profile under a new version gets a distinct reference and stays attributable
 * to the older one.
 *
 * EXPERIMENTAL. The shape may still change while extraction runs (DICE #67) land.
 *
 * @property name Host-defined profile name, stable across versions of the same profile
 * @property version Host-defined version of that profile
 */
@ApiStatus.Experimental
data class ExtractionContentProfileRef(
    val name: String,
    val version: String,
) {

    init {
        require(name.isNotBlank()) { "name must not be blank" }
        require(name.length <= MAX_NAME_LENGTH) {
            "name must be at most $MAX_NAME_LENGTH characters, was ${name.length}"
        }
        require(version.isNotBlank()) { "version must not be blank" }
        require(version.length <= MAX_VERSION_LENGTH) {
            "version must be at most $MAX_VERSION_LENGTH characters, was ${version.length}"
        }
    }

    companion object {

        /**
         * Longest profile name DICE accepts. A reference is an identifier the host mints, not
         * a place to smuggle a payload, and extraction runs will store these — so the bound
         * exists to keep a stored run header from growing without limit. Any real profile name
         * is far shorter.
         */
        const val MAX_NAME_LENGTH: Int = 256

        /**
         * Longest profile version DICE accepts. Same reasoning as [MAX_NAME_LENGTH]; versions
         * are short by nature (`v3`, `2026-08-01`, a commit sha).
         */
        const val MAX_VERSION_LENGTH: Int = 64
    }
}
