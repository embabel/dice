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
package com.embabel.dice.storage.autoconfigure

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * How far the schema-governance loop is allowed to go.
 *
 * Governance escalates in tiers, and an operator picks the tier. Each tier is useful on its own: an
 * application can report drift for a year and quarantine nothing.
 */
enum class DriftMode {

    /**
     * No drift checking at all. Stamps and stores are still wired, so an application can record and
     * read schema versions, but nothing compares the declaration against a live graph.
     */
    OFF,

    /**
     * The default. Check and report, touching no proposition. Ask the runner wired here for a live
     * run and it downgrades to a dry run, logs the downgrade, and reports back `dryRun = true`.
     */
    OBSERVE,

    /**
     * Check, report, and let a caller quarantine. The runner honours `run(dryRun = false)`, which
     * moves stranded propositions to `STALE` with a reason. Nothing runs on its own; a caller has
     * to ask.
     */
    QUARANTINE,
}

/**
 * Settings for the metamodel governance loop.
 *
 * None of this switches governance on by itself. The loop is wired only when the application
 * supplies a `DeclaredSchemaSource` bean, whatever these properties say. They control what happens
 * once one exists.
 */
@ConfigurationProperties(prefix = "embabel.dice.metamodel")
data class MetamodelProperties(

    /**
     * Kill switch. `false` removes every metamodel bean even when a `DeclaredSchemaSource` is
     * present, so governance can be switched off for one environment while the bean stays in place.
     */
    val enabled: Boolean = true,

    /** Drift checking: how far a check is allowed to go. */
    val drift: DriftProperties = DriftProperties(),
) {

    /** Drift-check settings. */
    data class DriftProperties(

        /**
         * The escalation tier: `off`, `observe` (the default), or `quarantine`. See [DriftMode].
         *
         * The default is `observe`. Reporting is safe to leave running indefinitely; changing
         * proposition state is a decision somebody makes on purpose. Defaulting to `quarantine`
         * would let a mistyped schema strand real knowledge on the next check.
         */
        val mode: DriftMode = DriftMode.OBSERVE,
    )
}
