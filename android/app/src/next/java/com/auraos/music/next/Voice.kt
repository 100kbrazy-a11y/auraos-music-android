package com.auraos.music.next

/** The word-timing engine (cpp/voice): plain C, no model and no libraries. It finds where the voice is in a song and where its syllables begin. */
object Voice {
    @Volatile private var tried = false
    @Volatile var loaded = false; private set
    fun load(): Boolean {
        if (loaded) return true
        if (tried) return false
        tried = true
        return try { System.loadLibrary("strata_voice"); loaded = true; true } catch (_: Throwable) { false }
    }
    /** left / right: [n] 16-bit samples at [sampleRate] Hz. Returns a handle (0 when there is too little audio); free it with [nFree] */
    @JvmStatic external fun nAnalyze(left: ShortArray, right: ShortArray, n: Int, sampleRate: Int): Long
    @JvmStatic external fun nFree(v: Long)
    @JvmStatic external fun nSyllables(word: String): Int
    /** seconds to ADD to the lyrics' line times so they agree with the singing (0 when they already do); used[0] = how many lines gave a reading */
    @JvmStatic external fun nResidual(v: Long, bases: DoubleArray, used: IntArray): Double
    /** lines one line up against the voice: 0 = nothing sung there, 1 = words spread over the singing, 2 = matched to syllables */
    @JvmStatic external fun nAlignLine(v: Long, ws: Double, we: Double, after: Double, base: Double, slack: Double, syll: IntArray, quick: IntArray, starts: DoubleArray, ends: DoubleArray, endOut: DoubleArray): Int
}
