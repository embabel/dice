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
package com.embabel.dice.spi

/**
 * An in-memory [ConnectedComponentsFinder] that unions the endpoints of every non-vetoed edge
 * with a simple union-find, then reports each id's component as its root.
 *
 * This mirrors the union-find semantics in
 * [com.embabel.dice.projection.memory.DuplicateCollectorStrategy]: vetoed edges never union
 * their endpoints, the merge direction is deterministic (the smaller id becomes the root), and
 * path compression keeps lookups cheap. Only ids present in `propositionIds` are returned, and
 * an id that appears in no surviving edge is its own singleton component.
 */
class InMemoryConnectedComponentsFinder : ConnectedComponentsFinder {

    override fun findComponents(
        runId: String,
        propositionIds: Set<String>,
        edges: List<CollectorCandidateEdge>,
    ): Map<String, String> {
        val unionFind = UnionFind(propositionIds)
        for (edge in edges) {
            if (edge.vetoed) continue
            if (edge.anchorId !in propositionIds || edge.memberId !in propositionIds) continue
            unionFind.union(edge.anchorId, edge.memberId)
        }
        return propositionIds.associateWith { unionFind.find(it) }
    }

    /**
     * Union-find over a fixed set of ids, seeded so every id starts as its own root. Merge
     * direction is deterministic (smaller id wins) so the same edges always produce the same
     * component ids, and path compression keeps repeated lookups cheap.
     */
    private class UnionFind(ids: Set<String>) {
        private val parent = ids.associateWithTo(mutableMapOf()) { it }

        fun find(id: String): String {
            var root = id
            while (parent.getValue(root) != root) {
                root = parent.getValue(root)
            }
            var cur = id
            while (cur != root) {
                val next = parent.getValue(cur)
                parent[cur] = root
                cur = next
            }
            return root
        }

        fun union(a: String, b: String) {
            val rootA = find(a)
            val rootB = find(b)
            if (rootA != rootB) {
                if (rootA <= rootB) parent[rootB] = rootA else parent[rootA] = rootB
            }
        }
    }
}
