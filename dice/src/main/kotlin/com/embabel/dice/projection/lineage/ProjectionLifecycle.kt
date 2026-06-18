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
package com.embabel.dice.projection.lineage

/**
 * Lifecycle state of a single proposition-to-target projection.
 *
 * Allows consumers to keep projected views aligned with the current state of a
 * proposition and to determine whether a given projection is still valid.
 */
enum class ProjectionLifecycle {

    /** The proposition was projected to the target as a newly created artifact. */
    PROJECTED,

    /** The projection was aligned onto / adopted an existing artifact in the target. */
    ADOPTED,

    /** The proposition was intentionally not projected (did not meet criteria). */
    SKIPPED,

    /** Projection to the target failed (error or incompatibility). */
    FAILED,

    /**
     * A previously valid projection is now out of date because the underlying
     * proposition changed (e.g. superseded, contradicted, or re-extracted).
     */
    STALE
}
