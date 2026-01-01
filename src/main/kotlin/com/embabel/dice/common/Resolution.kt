package com.embabel.dice.common

import com.embabel.common.core.types.HasInfoString

/**
 * Resolution
 */
interface Resolution<S, R> : HasInfoString {
    val suggested: S
    val existing: R?
    val recommended: R?
}