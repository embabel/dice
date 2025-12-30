package com.embabel.dice.text2graph

/**
 * Convergence between a resolution, with an ultimate convergence target
 * that we can write a store, whether persistent or in memory.
 * @param resolution the resolution
 * @param convergenceTarget the ultimate convergence target, or null if no convergence
 * (for example, a new entity was vetoed)
 */
data class Merge<R : Resolution<*, T>, T>(
    val resolution: R,
    val convergenceTarget: T?
)

data class Merges<R : Resolution<*, T>, T>(
    val merges: List<Merge<R, T>>,
)
