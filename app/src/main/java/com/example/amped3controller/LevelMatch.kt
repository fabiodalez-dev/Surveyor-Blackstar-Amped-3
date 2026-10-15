package com.example.amped3controller

import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Turns a loudness correction into a Cabinet Level, without losing the level the user chose.
 *
 * The pedal holds one number, the level actually playing. The user's own level is that number
 * minus the correction the app last applied, so moving the fader in between simply moves the
 * user's level. A correction is never positive and the result is never above the user's level:
 * levelling only ever turns a cabinet down. When the applied correction is not known, after a
 * slot recall or a restart, it counts as zero, which can only make the next level lower.
 */
object LevelMatch {
    /** Cabinet Level steps for [db]; one step is 24/127 dB (see [AmpedProtocol.levelDb]). */
    fun steps(db: Double): Int = min(0, (db * 127.0 / 24.0).roundToInt())

    /** The level to write and the correction it carries, given the level playing [now], the
     *  correction already in it [applied] and the one wanted for the next cabinet [target]. */
    data class Plan(val own: Int, val level: Int) { val applied get() = level - own }

    fun plan(now: Int, applied: Int, target: Int): Plan {
        val own = (now - applied).coerceIn(0, 127)
        return Plan(own, (own + min(target, 0)).coerceIn(0, own))
    }
}
