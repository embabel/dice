package com.embabel.dice.common

import com.embabel.common.core.types.Timestamped
import com.fasterxml.jackson.annotation.JsonTypeInfo
import java.time.Instant

@JsonTypeInfo(
    use = JsonTypeInfo.Id.CLASS,
    include = JsonTypeInfo.As.PROPERTY,
    property = "fqn"
)
interface DiceEvent : Timestamped

data class ContradictionEvent(
    override val timestamp: Instant = Instant.now(),
) : DiceEvent

fun interface DiceEventListener {
    fun onEvent(event: DiceEvent)

    companion object {
        val DEV_NULL: DiceEventListener = DevNull
    }
}

private object DevNull : DiceEventListener {
    override fun onEvent(event: DiceEvent) {
        // Do nothing
    }
}
