package com.bqsqwad.ai.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DictationStateTest {
    @Test
    fun startRecordingAndTickCapsAt25Seconds() {
        val started = 1_000_000L
        var state = DictationState.start(sessionId = 3, nowMs = started)
        assertTrue(state.isRecording)
        state = DictationState.tick(state, nowMs = started + 30_000L)
        val recording = state as DictationState.Recording
        assertEquals(25, recording.seconds)
    }

    @Test
    fun transcribeThenReady() {
        var state: DictationState = DictationState.start(1, 0L)
        state = DictationState.beginTranscribe(state)
        assertTrue(state.isTranscribing)
        state = DictationState.ready(1, "  hello  ")
        val ready = state as DictationState.ReadyToSend
        assertEquals("hello", ready.transcript)
        assertTrue(state.canShowVoiceBar)
    }

    @Test
    fun cancelReturnsIdle() {
        assertEquals(DictationState.Idle, DictationState.cancel())
        assertFalse(DictationState.Idle.isRecording)
    }
}
