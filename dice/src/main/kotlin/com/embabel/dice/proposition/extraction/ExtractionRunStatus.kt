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
 * Where an extraction run stands.
 *
 * These are the four values, and only the values. Which transitions are legal, which are
 * compare-and-set, and what a store does with a repeated terminal write all belong to the run
 * store contract that lands with DICE #67's next slice. Nothing here encodes a transition rule,
 * so the state machine has one place to define them.
 *
 * MLflow's run status carries five values — `RUNNING`, `SCHEDULED`, `FINISHED`, `FAILED`,
 * `KILLED`. DICE has four for two reasons. There is no scheduler, so nothing can observe a run
 * between "requested" and "started" and `SCHEDULED` would never be written. And a run stopped
 * from outside is recorded as [CANCELLED], because the fact an operator or an audit cares about
 * is that the run stopped short of its products, which is the same fact whichever side pressed
 * stop.
 *
 * EXPERIMENTAL. The shape may still change while extraction runs (DICE #67) land.
 */
@ApiStatus.Experimental
enum class ExtractionRunStatus {

    /**
     * The run started and has not reached a terminal state. A run whose process died mid-way
     * stays here until something moves it, which is what makes it retryable.
     */
    RUNNING,

    /**
     * Every product the run's request called for is either durably persisted or terminally
     * disposed.
     *
     * That is the definition the store contract enforces, and it is stated here so the meaning
     * travels with the value. The consequence worth knowing at the model layer: COMPLETED is
     * written after persistence, never before, so a run whose persistence never finished stays
     * [RUNNING] rather than claiming products it does not have. A run with zero products
     * terminalizes COMPLETED — vacuously, since there was nothing left to persist.
     */
    COMPLETED,

    /** The run stopped on an error it could not get past. Its recorded failures say which. */
    FAILED,

    /**
     * The run stopped before finishing, by request or by external termination. This is also the
     * abandonment path for a partially successful run nobody intends to finish: its outstanding
     * products stay outstanding behind it, and recovery goes through a new run linked by parent
     * or superseded reference.
     */
    CANCELLED,
    ;

    /** True for the three states a run does not leave. */
    val isTerminal: Boolean
        get() = this != RUNNING
}
