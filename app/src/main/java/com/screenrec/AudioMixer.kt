package com.screenrec

/** ผสมสัญญาณเสียง PCM16 สองชุดเป็นชุดเดียวก่อนเข้า AAC encoder */
object AudioMixer {
    fun mix(a: ShortArray?, b: ShortArray?, out: ShortArray, n: Int) {
        for (i in 0 until n) {
            val s = (a?.get(i)?.toInt() ?: 0) + (b?.get(i)?.toInt() ?: 0)
            out[i] = s.coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
        }
    }
}
