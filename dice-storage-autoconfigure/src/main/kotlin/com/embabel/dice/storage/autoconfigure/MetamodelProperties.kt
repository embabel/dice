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
 * Whether the wiring registers a drift-check runner.
 *
 * There are two settings because there are two useful answers. Some applications want schema
 * history and nothing else; most want the check as well. Neither setting can quarantine anything: a
 * check reads, compares and writes a report, and moving a proposition is a separate call a host
 * makes on `DriftSweepCapable` at a moment it picks.
 */
enum class DriftMode {

    /**
     * Stamps and stores only. The version store, the drift log and the differ are wired, so an
     * application can record and read schema versions, and no runner bean is registered, so nothing
     * compares the declaration against a live graph.
     */
    OFF,

    /**
     * The default. A [com.embabel.dice.metamodel.DriftCheckRunner] bean is registered. Ask it for a
     * check and it stamps the declaration, snapshots the graph, compares, and writes a drift report.
     * It touches no proposition on any path.
     */
    OBSERVE,
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

    /** Drift checking: whether a runner is wired at all. */
    val drift: DriftProperties = DriftProperties(),
) {

    /** Drift-check settings. */
    data class DriftProperties(

        /**
         * `off` or `observe` (the default). See [DriftMode].
         *
         * `off` is for an application that wants schema stamps and history without a drift check
         * running against its graph.
         */
        val mode: DriftMode = DriftMode.OBSERVE,
    )
}
