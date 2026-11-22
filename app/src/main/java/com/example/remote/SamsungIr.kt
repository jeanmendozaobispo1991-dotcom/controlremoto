package com.example.remote

import android.content.Context
import android.hardware.ConsumerIrManager

/** Envía códigos infrarrojos Samsung32 usando el emisor IR del celular. */
class SamsungIr(context: Context) {

    private val ir = context.getSystemService(Context.CONSUMER_IR_SERVICE) as? ConsumerIrManager

    val available: Boolean
        get() = ir?.hasIrEmitter() == true

    fun send(code: Long) {
        val manager = ir ?: return
        if (!manager.hasIrEmitter()) return

        val pattern = ArrayList<Int>()
        pattern.add(4500) // cabecera
        pattern.add(4500)
        for (i in 31 downTo 0) {
            pattern.add(560)
            pattern.add(if (((code shr i) and 1L) == 1L) 1690 else 560)
        }
        pattern.add(560) // marca final

        manager.transmit(38000, pattern.toIntArray())
    }

    companion object {
        const val POWER = 0xE0E040BFL
        const val SOURCE = 0xE0E0807FL
        const val VOL_UP = 0xE0E0E01FL
        const val VOL_DOWN = 0xE0E0D02FL
        const val MUTE = 0xE0E0F00FL
        const val CH_UP = 0xE0E048B7L
        const val CH_DOWN = 0xE0E008F7L

        const val UP = 0xE0E006F9L
        const val DOWN = 0xE0E08679L
        const val LEFT = 0xE0E0A659L
        const val RIGHT = 0xE0E046B9L
        const val OK = 0xE0E016E9L
        const val BACK = 0xE0E01AE5L
        const val HOME = 0xE0E09E61L
        const val MENU = 0xE0E058A7L

        val NUMBERS = longArrayOf(
            0xE0E08877L, // 0
            0xE0E020DFL, // 1
            0xE0E0A05FL, // 2
            0xE0E0609FL, // 3
            0xE0E010EFL, // 4
            0xE0E0906FL, // 5
            0xE0E050AFL, // 6
            0xE0E030CFL, // 7
            0xE0E0B04FL, // 8
            0xE0E0708FL  // 9
        )
    }
}
