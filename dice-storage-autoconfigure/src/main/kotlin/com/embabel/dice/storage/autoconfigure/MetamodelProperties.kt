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
 * Configuration for the metamodel drift-check feature.
 *
 * `embabel.dice.metamodel.enabled` gates the whole feature: off by default, so a live graph never
 * gets introspected unless an app opts in. There is no scheduler wired by this auto-configuration --
 * an app calls `DriftCheckRunner.run(dryRun)` itself (on a cron, a startup hook, an admin endpoint,
 * whatever fits) and is expected to read [dryRun] and [schemaName] from here to decide how and what
 * to check.
 */
@ConfigurationProperties(prefix = "embabel.dice.metamodel")
data class MetamodelProperties(
    /** Whether the drift-check beans are wired up at all. Off by default. */
    var enabled: Boolean = false,

    /** Whether a consumer's call to `DriftCheckRunner.run` should default to preview-only. */
    var dryRun: Boolean = true,

    /** Which declared schema to check, for a consumer whose [DeclaredSchemaSource][com.embabel.dice.metamodel.DeclaredSchemaSource] serves more than one. */
    var schemaName: String? = null,
)
