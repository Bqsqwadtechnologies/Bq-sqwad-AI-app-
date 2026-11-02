package com.bqsqwad.ai.core

/**
 * Single source of truth for chat dictation UI / recorder flow (Phase 1 voice mode).
 *
 * Composer should keep one `DictationState` and derive UI from these helpers
 * instead of independent `dictationRecording` / `dictationTranscribing` booleans.
 *
 * Flow:
 * Idle → Recording (mic) → stop → hold PCM in memory → Transcribing → ReadyToSend → Send/Cancel → Idle
 * Failed may appear after stop or transcribe errors; Cancel always returns Idle.
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
    val isReadyToSend: Boolean get() = this is ReadyToSend
    val isFailed: Boolean get() = this is Failed
    val canShowVoiceBar: Boolean get() = this is Recording || this is Transcribing || this is ReadyToSend
    /** Cancel + Transcribe after stop, before recognition. */
    val showCancelTranscribe: Boolean get() = this is Idle // used with non-null in-memory wav; prefer explicit wav check in UI
    /** Cancel + Send after recognition fills the composer. */
    val showCancelSend: Boolean get() = this is ReadyToSend

    val sessionIdOrZero: Int
        get() = when (this) {
            is Recording -> sessionId
            is Transcribing -> sessionId
            is ReadyToSend -> sessionId
            is Failed -> sessionId
            Idle -> 0
        }

    val errorMessage: String?
        get() = (this as? Failed)?.message

    val needsCredits: Boolean
        get() = (this as? Failed)?.needsCredits == true

    val recordingSeconds: Int
        get() = (this as? Recording)?.seconds ?: 0

    val recordingLevel: Float
        get() = (this as? Recording)?.level ?: 0f

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

        fun beginTranscribe(sessionId: Int): DictationState = Transcribing(sessionId)

        fun ready(sessionId: Int, transcript: String): DictationState =
            ReadyToSend(sessionId, transcript.trim())

        fun fail(sessionId: Int, message: String, needsCredits: Boolean = false): DictationState =
            Failed(sessionId, message, needsCredits)

        fun cancel(): DictationState = Idle
    }
}
