package com.auraos.music.next

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

/**
 * The WebView app's haptics, same kinds and same moments (native-chrome.js / main.js):
 *  tick – sliders, chips, switches, crossing a "let go now" point (at most one per 45 ms)
 *  soft / light – back, dismissing, quiet changes        tap – every button, row, card
 *  press / medium / play – long-press, play / pause      thud – heavy: breaking loose, deleting
 *  success / error – likes, saves, things that failed
 */
object Hap {
    private var vib: Vibrator? = null
    private var lastTick = 0L
    fun init(c: Context) {
        vib = if (Build.VERSION.SDK_INT >= 31) (c.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)?.defaultVibrator
        else @Suppress("DEPRECATION") (c.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator)
    }
    fun tick() = go("tick"); fun soft() = go("soft"); fun tap() = go("tap"); fun press() = go("press"); fun thud() = go("thud")
    fun success() = go("success"); fun error() = go("error")

    fun go(kind: String) {
        if (!Cfg.haptics.v.value) return
        val v = vib ?: return
        if (!v.hasVibrator()) return
        if (kind == "tick") { val now = System.currentTimeMillis(); if (now - lastTick < 45) return; lastTick = now }
        try {
            if (Build.VERSION.SDK_INT >= 30 && v.areAllPrimitivesSupported(VibrationEffect.Composition.PRIMITIVE_CLICK, VibrationEffect.Composition.PRIMITIVE_TICK)) {
                val c = VibrationEffect.startComposition()
                val thud = Build.VERSION.SDK_INT >= 31 && v.areAllPrimitivesSupported(VibrationEffect.Composition.PRIMITIVE_THUD)
                when (kind) {
                    "tick" -> c.addPrimitive(VibrationEffect.Composition.PRIMITIVE_TICK, 0.7f)
                    "soft", "light" -> c.addPrimitive(VibrationEffect.Composition.PRIMITIVE_CLICK, 0.35f)
                    "tap" -> c.addPrimitive(VibrationEffect.Composition.PRIMITIVE_CLICK, 0.6f)
                    "press", "medium", "play" -> c.addPrimitive(VibrationEffect.Composition.PRIMITIVE_CLICK, 1f)
                    "thud" -> if (thud) c.addPrimitive(VibrationEffect.Composition.PRIMITIVE_THUD, 1f) else c.addPrimitive(VibrationEffect.Composition.PRIMITIVE_CLICK, 1f)
                    "success" -> { c.addPrimitive(VibrationEffect.Composition.PRIMITIVE_CLICK, 0.6f); c.addPrimitive(VibrationEffect.Composition.PRIMITIVE_CLICK, 1f, 70) }
                    "error" -> { c.addPrimitive(VibrationEffect.Composition.PRIMITIVE_CLICK, 1f); c.addPrimitive(VibrationEffect.Composition.PRIMITIVE_CLICK, 1f, 90); c.addPrimitive(VibrationEffect.Composition.PRIMITIVE_CLICK, 1f, 90) }
                    else -> c.addPrimitive(VibrationEffect.Composition.PRIMITIVE_CLICK, 0.5f)
                }
                v.vibrate(c.compose()); return
            }
            if (Build.VERSION.SDK_INT >= 29) {
                v.vibrate(VibrationEffect.createPredefined(when (kind) {
                    "tick" -> VibrationEffect.EFFECT_TICK; "thud", "press", "medium", "play" -> VibrationEffect.EFFECT_HEAVY_CLICK
                    "success", "error" -> VibrationEffect.EFFECT_DOUBLE_CLICK; else -> VibrationEffect.EFFECT_CLICK
                })); return
            }
            if (Build.VERSION.SDK_INT >= 26) v.vibrate(VibrationEffect.createOneShot(when (kind) { "tick" -> 8L; "thud", "press" -> 30L; "success", "error" -> 40L; else -> 15L }, VibrationEffect.DEFAULT_AMPLITUDE))
        } catch (_: Throwable) { }
    }

    /** the tugging buzz while you pull the media bar: [k] 0..1 grows the strength */
    fun tug(k: Float) {
        val v = vib ?: return
        try {
            if (Build.VERSION.SDK_INT >= 30 && v.areAllPrimitivesSupported(VibrationEffect.Composition.PRIMITIVE_TICK))
                v.vibrate(VibrationEffect.startComposition().addPrimitive(VibrationEffect.Composition.PRIMITIVE_TICK, (0.15f + 0.85f * k).coerceIn(0f, 1f)).compose())
            else if (Build.VERSION.SDK_INT >= 26) v.vibrate(VibrationEffect.createOneShot(6L + (10 * k).toLong(), (40 + 200 * k).toInt().coerceIn(1, 255)))
        } catch (_: Throwable) { }
    }

    /** like main.js snack(): messages that sound like a failure buzz "error", ones that sound like a success buzz "success" */
    fun forMessage(m: String) {
        val l = m.lowercase()
        if (Regex("^(couldn'?t|can'?t|failed|error|no |nothing)|isn'?t|wouldn'?t|refused|missing").containsMatchIn(l)) error()
        else if (Regex("^(added|saved|sent|made|created|imported|copied|signed in|found|renamed|updated|restored|downloaded)").containsMatchIn(l)) success()
    }
}

/** a tick when a drag crosses the point where letting go would do something, a soft one when it crosses back */
class Edge { private var on = false; fun set(now: Boolean) { if (now != on) { on = now; if (now) Hap.tick() else Hap.soft() } }; fun reset() { on = false } }
