package com.embabel.dice.common

import com.embabel.common.core.Sourced
import com.embabel.common.core.types.HasInfoString

data class Resolutions<R : HasInfoString>(
    override val chunkIds: Set<String>,
    val resolutions: List<R>,
) : HasInfoString, Sourced {

    override fun infoString(verbose: Boolean?, indent: Int): String {
        return "${javaClass.simpleName}(resolutions:\n\t${
            resolutions.joinToString("\n\t") { it.infoString(verbose) }
        })"

    }
}