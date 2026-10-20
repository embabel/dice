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
package com.embabel.dice.eval

import com.embabel.dice.proposition.PropositionRepository
import com.embabel.dice.storage.falkordb.FalkorDbPropositionRepository
import com.embabel.dice.storage.falkordb.FalkorDbTestGraph
import com.embabel.dice.storage.falkordb.FalkorDbTestServer
import org.junit.jupiter.api.AfterEach
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers

/** Runs the shared ingestion, projection, query, collector and report contract against FalkorDB. */
@Testcontainers
class FalkorDbCanonicalFlowTest : AbstractCanonicalFlowTest() {
    private val graphs = mutableListOf<FalkorDbTestGraph>()

    override fun newStore(): PropositionRepository {
        val graph = server.graph().also { graphs += it }
        return FalkorDbPropositionRepository(graph.objects, graph.persistence, newEmbeddingService())
    }

    @AfterEach
    fun closeGraphs() = graphs.forEach { it.close() }

    companion object {
        @Container
        @JvmField
        val server = FalkorDbTestServer()
    }
}
