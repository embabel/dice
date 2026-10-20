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
package com.embabel.dice.storage.falkordb

import com.embabel.dice.proposition.ContextSetReadCapable
import com.embabel.dice.proposition.Proposition
import com.embabel.dice.proposition.store.AbstractScopedReadConformanceTest
import org.junit.jupiter.api.AfterEach
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers

/** Runs the shared scoped read suite against FalkorDB, each test on a fresh graph. */
@Testcontainers
class FalkorDbScopedReadConformanceIntegrationTest : AbstractScopedReadConformanceTest() {
    private val graphs = mutableListOf<FalkorDbTestGraph>()

    override fun store(rows: List<Proposition>): ContextSetReadCapable {
        val graph = server.graph().also(graphs::add)
        return FalkorDbPropositionRepository(graph.objects, graph.persistence).apply { saveAll(rows) }
    }

    @AfterEach
    fun closeGraphs() {
        graphs.forEach(FalkorDbTestGraph::close)
        graphs.clear()
    }

    companion object {
        @Container
        @JvmField
        internal val server = FalkorDbTestServer()
    }
}
