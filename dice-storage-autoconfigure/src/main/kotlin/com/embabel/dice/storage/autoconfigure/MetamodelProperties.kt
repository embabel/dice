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
 * Governance escalates in tiers, and the tier is a decision an operator makes rather than something
 * the framework guesses. Each one is only safe on top of the one below it, and each is worth having
 * on its own — you can report drift for a year without ever quarantining anything.
 */
enum class DriftMode {

    /**
     * No drift checking at all. Stamps and stores are still wired, so an application can record and
     * read schema versions, but nothing compares the declaration against a live graph.
     */
    OFF,

    /**
     * Check and report, never touch a proposition. The default. The runner you get here refuses to
     * do a live run: ask it for one and it downgrades to a dry run, says so in the log, and reports
     * back `dryRun = true`.
     */
    OBSERVE,

    /**
     * Check, report, and let a caller quarantine. The runner honours `run(dryRun = false)`, which
     * moves stranded propositions to `STALE` with a reason. Still nothing runs on its own — a
     * caller has to ask.
     */
    QUARANTINE,
}

/**
 * Settings for the metamodel governance loop.
 *
 * None of this switches governance on by itself. The loop is wired only when the application
 * supplies a `DeclaredSchemaSource` bean — no declared schema, no governance, whatever these
 * properties say. What they control is what happens once one exists.
 */
@ConfigurationProperties(prefix = "embabel.dice.metamodel")
data class MetamodelProperties(

    /**
     * Kill switch. `false` removes every metamodel bean even when a `DeclaredSchemaSource` is
     * present — the way to turn governance off for one environment without deleting the bean.
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
         * Quarantine is never the default. Reporting can't hurt anything, so it's safe to leave on
         * forever; changing proposition state is a decision somebody has to make on purpose, and
         * making it the default would mean a schema someone mistyped could strand real knowledge on
         * the next scheduled check.
         */
        val mode: DriftMode = DriftMode.OBSERVE,
    )
}
