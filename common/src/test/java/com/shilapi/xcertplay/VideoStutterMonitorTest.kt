package com.shilapi.xcertplay

import com.shilapi.xcertplay.media.LiveVideoCounters
import com.shilapi.xcertplay.media.LiveVideoReading
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VideoStutterMonitorTest {
    private var fps = 30
    private var resolutionPercent = 100

    private fun monitor(): VideoStutterMonitor = VideoStutterMonitor({ fps }, { resolutionPercent })

    private fun second(nanos: Long) = nanos * 1_000_000_000L

    /** Cumulative totals rising [received] and [shown] per second; decode takes [decodeMillis] per frame. */
    private fun totals(receivedPerSecond: Long, shownPerSecond: Long = receivedPerSecond, decodeMillis: Long = 10): LiveVideoCounters {
        val total = receivedAt + shownAt + decodeAt
        receivedAt += receivedPerSecond
        shownAt += shownPerSecond
        decodeAt += receivedPerSecond * decodeMillis * 1_000_000
        return LiveVideoCounters(received = receivedAt, rendered = shownAt, decodeNanos = decodeAt, decodeSamples = shownAt)
    }

    private var receivedAt = 0L
    private var shownAt = 0L
    private var decodeAt = 0L

    private fun losingSecond() = totals(receivedPerSecond = 56, shownPerSecond = 20)

    private fun healthySecond() = totals(receivedPerSecond = 56)

    @Test fun aHealthyStreamNeverSuggests() {
        val monitor = monitor()
        assertNull(monitor.onCounters(totals(0, 0), second(0)))
        for (tick in 1..30L) assertNull(monitor.onCounters(healthySecond(), second(tick)))
    }

    @Test fun sustainedLostFramesSuggestTheFrameRateFirst() {
        fps = 60
        val monitor = monitor()
        assertNull(monitor.onCounters(totals(0, 0), second(0)))
        for (tick in 1..19L) assertNull(monitor.onCounters(losingSecond(), second(tick)))
        assertEquals(StutterSuggestion.FrameRate(30), monitor.onCounters(losingSecond(), second(20)))
    }

    @Test fun sustainedSlowDecodingSuggestsTheResolutionWhenFrameRateIsAlreadyLow() {
        val monitor = monitor()
        assertNull(monitor.onCounters(totals(0, 0), second(0)))
        // 20 frames a second, each taking 40 ms to come back: slow, but none lost.
        for (tick in 1..19L) assertNull(monitor.onCounters(totals(20, 20, decodeMillis = 40), second(tick)))
        assertEquals(StutterSuggestion.Resolution(80), monitor.onCounters(totals(20, 20, decodeMillis = 40), second(20)))
    }

    @Test fun theWindowNeedsItsFullQuorumBeforeFiring() {
        val monitor = monitor()
        assertNull(monitor.onCounters(totals(0, 0), second(0)))
        // Fourteen losing seconds inside a full window are not enough, even as the newest samples.
        for (tick in 1..14L) assertNull(monitor.onCounters(losingSecond(), second(tick)))
        for (tick in 15..20L) assertNull(monitor.onCounters(healthySecond(), second(tick)))
    }

    @Test fun aSuggestionFiresOnceUntilTheWatchResets() {
        fps = 60
        val monitor = monitor()
        assertNull(monitor.onCounters(totals(0, 0), second(0)))
        for (tick in 1..19L) assertNull(monitor.onCounters(losingSecond(), second(tick)))
        assertEquals(StutterSuggestion.FrameRate(30), monitor.onCounters(losingSecond(), second(20)))
        assertNull(monitor.onCounters(losingSecond(), second(21)))
        // A new decoder or session starts the watch over.
        monitor.reset()
        assertNull(monitor.onCounters(totals(0, 0), second(0)))
        for (tick in 1..19L) assertNull(monitor.onCounters(losingSecond(), second(tick)))
        assertEquals(StutterSuggestion.FrameRate(30), monitor.onCounters(losingSecond(), second(20)))
    }

    @Test fun aCounterSetbackClearsTheWindow() {
        fps = 60
        val monitor = monitor()
        assertNull(monitor.onCounters(totals(0, 0), second(0)))
        for (tick in 1..14L) assertNull(monitor.onCounters(losingSecond(), second(tick)))
        for (tick in 15..20L) assertNull(monitor.onCounters(healthySecond(), second(tick)))
        // A new decoder starts from zero: the meter only sets a baseline, and the window empties.
        receivedAt = 0
        shownAt = 0
        decodeAt = 0
        assertNull(monitor.onCounters(totals(0, 0), second(21)))
        for (tick in 1..19L) assertNull(monitor.onCounters(losingSecond(), second(21 + tick)))
        assertEquals(StutterSuggestion.FrameRate(30), monitor.onCounters(losingSecond(), second(41)))
    }

    @Test fun aHeadUnitWithNothingLeftToLowerIsNotWatched() {
        fps = 30
        resolutionPercent = 60
        val monitor = monitor()
        assertNull(monitor.onCounters(totals(0, 0), second(0)))
        for (tick in 1..30L) assertNull(monitor.onCounters(losingSecond(), second(tick)))
    }

    @Test fun theStepDownWalksFrameRateThenResolution() {
        assertEquals(StutterSuggestion.FrameRate(30), stutterSuggestion(currentFps = 60, resolutionPercent = 100))
        assertEquals(StutterSuggestion.Resolution(80), stutterSuggestion(currentFps = 30, resolutionPercent = 100))
        assertEquals(StutterSuggestion.Resolution(60), stutterSuggestion(currentFps = 30, resolutionPercent = 75))
        assertNull(stutterSuggestion(currentFps = 30, resolutionPercent = 60))
        assertNull(stutterSuggestion(currentFps = 30, resolutionPercent = 50))
    }

    @Test fun theSamplePredicatesMatchOnlySustainedShapes() {
        fun reading(shown: Int, received: Int, decodeMillis: Int) =
            LiveVideoReading(shownFps = shown, receivedFps = received, decodeMillis = decodeMillis)
        // Lost frames: content moving at 56 fps, only 20 shown.
        assertEquals(true, stutterLosesFrames(reading(shown = 20, received = 56, decodeMillis = 10)))
        assertEquals(false, stutterLosesFrames(reading(shown = 45, received = 56, decodeMillis = 10)))
        assertEquals(false, stutterLosesFrames(reading(shown = 5, received = 20, decodeMillis = 10)))
        // Slow decoding: a 30 fps slot is about 33 ms, and untimed frames never count.
        assertEquals(true, stutterDecodesSlowly(reading(shown = 20, received = 20, decodeMillis = 34)))
        assertEquals(true, stutterDecodesSlowly(reading(shown = 20, received = 20, decodeMillis = 33)))
        assertEquals(false, stutterDecodesSlowly(reading(shown = 20, received = 20, decodeMillis = 32)))
        assertEquals(false, stutterDecodesSlowly(reading(shown = 20, received = 10, decodeMillis = 90)))
        assertEquals(false, stutterDecodesSlowly(reading(shown = 20, received = 20, decodeMillis = -1)))
    }
}
