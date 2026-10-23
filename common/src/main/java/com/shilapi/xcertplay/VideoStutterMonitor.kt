package com.shilapi.xcertplay

import com.shilapi.xcertplay.media.LiveVideoCounters
import com.shilapi.xcertplay.media.LiveVideoMeter
import com.shilapi.xcertplay.media.LiveVideoReading

/** The next display step down, offered once when the picture keeps stuttering; applied at a reconnect. */
internal sealed interface StutterSuggestion {
    /** Lower the frame-rate setting. */
    data class FrameRate(val fps: Int) : StutterSuggestion
    /** Lower the resolution setting. */
    data class Resolution(val percent: Int) : StutterSuggestion
}

/**
 * The deterministic step down for the current display settings: the frame rate first (it saves the most
 * decode and transmission work per picture quality), then the resolution. Null once both are already low,
 * so nothing monitors a head unit that has nothing left to lower.
 */
internal fun stutterSuggestion(currentFps: Int, resolutionPercent: Int): StutterSuggestion? = when {
    currentFps > 30 -> StutterSuggestion.FrameRate(30)
    resolutionPercent > 80 -> StutterSuggestion.Resolution(80)
    resolutionPercent > 60 -> StutterSuggestion.Resolution(60)
    else -> null
}

/** One sample of sustained lost frames: content is moving, yet most of it never reaches the screen. */
internal fun stutterLosesFrames(reading: LiveVideoReading): Boolean =
    reading.receivedFps >= STUTTER_LOSS_RX_FPS && reading.shownFps * 5 < reading.receivedFps * 4

/** One sample of sustained slow decoding: a frame takes longer than a 30 fps slot to come back. */
internal fun stutterDecodesSlowly(reading: LiveVideoReading): Boolean =
    reading.receivedFps >= STUTTER_SLOW_RX_FPS && reading.decodeMillis >= STUTTER_SLOW_DECODE_MILLIS

/**
 * Watches the main screen's live counters while CarPlay runs. One-second samples fill a sliding window;
 * when the picture loses frames or decodes slowly through nearly the whole window, the monitor offers the
 * next display step down — once, until [reset]. A gap in the counters (a new decoder, no decoder) clears
 * the window, so an old session's stutter cannot fire into a new one.
 */
internal class VideoStutterMonitor(
    private val currentFps: () -> Int,
    private val resolutionPercent: () -> Int,
) {
    private val meter = LiveVideoMeter()
    private val lossWindow = ArrayDeque<Boolean>()
    private val slowWindow = ArrayDeque<Boolean>()
    private var fired = false

    /** Feeds one sample; returns the session's single suggestion when the stutter has been sustained. */
    fun onCounters(counters: LiveVideoCounters?, nowNanos: Long = System.nanoTime()): StutterSuggestion? {
        if (fired) return null
        if (stutterSuggestion(currentFps(), resolutionPercent()) == null) {
            reset()
            return null
        }
        val reading = meter.update(counters, nowNanos)
        if (reading == null) {
            // The first sample, a new decoder or no decoder: an old window must not fire into it.
            lossWindow.clear()
            slowWindow.clear()
            return null
        }
        lossWindow.addLast(stutterLosesFrames(reading))
        slowWindow.addLast(stutterDecodesSlowly(reading))
        if (lossWindow.size > STUTTER_WINDOW_SAMPLES) {
            lossWindow.removeFirst()
            slowWindow.removeFirst()
        }
        val sustained = { window: ArrayDeque<Boolean> ->
            window.size == STUTTER_WINDOW_SAMPLES && window.count { it } >= STUTTER_WINDOW_QUORUM
        }
        if (!sustained(lossWindow) && !sustained(slowWindow)) return null
        fired = true
        return stutterSuggestion(currentFps(), resolutionPercent())
    }

    /** A new decoder or a new session starts the watch over. */
    fun reset() {
        meter.reset()
        lossWindow.clear()
        slowWindow.clear()
        fired = false
    }
}

private const val STUTTER_WINDOW_SAMPLES = 20
private const val STUTTER_WINDOW_QUORUM = 15
private const val STUTTER_LOSS_RX_FPS = 24
private const val STUTTER_SLOW_RX_FPS = 15
private const val STUTTER_SLOW_DECODE_MILLIS = 33
