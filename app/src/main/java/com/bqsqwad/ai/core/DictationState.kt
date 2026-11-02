package com.bqsqwad.ai.core

/**
 * Single source of truth for chat dictation UI / recorder flow.
 * Map UI flags from [phase] instead of independent booleans.
 */
sealed class DictationState {
    data object Idle : DictationState()
    data class Recording(
        val sessionId: Int,
        val startedAtMs: Long,
        val seconds: Int = 0,
        val level: Float = 0f,
    ) : DictationState()
    data class Transcribing(val sessionId: Int) : DictationState()
    data class ReadyToSend(val sessionId: Int, val transcript: String) : DictationState()
    data class Failed(val sessionId: Int, val message: String, val needsCredits: Boolean = false) : DictationState()

    val isRecording: Boolean get() = this is Recording
    val isTranscribing: Boolean get() = this is Transcribing
    val canShowVoiceBar: Boolean get() = this is Recording || this is Transcribing || this is ReadyToSend

    companion object {
        const val MAX_SECONDS = 25

        fun start(sessionId: Int, nowMs: Long = System.currentTimeMillis()): DictationState =
            Recording(sessionId = sessionId, startedAtMs = nowMs)

        fun tick(state: DictationState, nowMs: Long = System.currentTimeMillis()): DictationState {
            if (state !is Recording) return state
            val seconds = (((nowMs - state.startedAtMs) / 1000L).toInt()).coerceIn(0, MAX_SECONDS)
            return state.copy(seconds = seconds)
        }

        fun level(state: DictationState, level: Float): DictationState {
            if (state !is Recording) return state
            return state.copy(level = level.coerceIn(0f, 1f))
        }

        fun beginTranscribe(state: DictationState): DictationState {
            val session = when (state) {
                is Recording -> state.sessionId
                is ReadyToSend -> state.sessionId
                is Failed -> state.sessionId
                else -> return state
            }
            return Transcribing(session)
        }

        fun ready(sessionId: Int, transcript: String): DictationState =
            ReadyToSend(sessionId, transcript.trim())

        fun fail(sessionId: Int, message: String, needsCredits: Boolean = false): DictationState =
            Failed(sessionId, message, needsCredits)

        fun cancel(): DictationState = Idle
    }
}
