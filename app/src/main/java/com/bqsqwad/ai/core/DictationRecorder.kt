package com.bqsqwad.ai.core

import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.MediaRecorder.AudioSource
import android.media.audiofx.NoiseSuppressor
import android.os.Handler
import android.os.Looper
import java.io.ByteArrayOutputStream
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.sqrt

/** Short-lived, in-memory mono PCM recorder for private dictation. No audio is written to disk. */
class DictationRecorder(context: Context) {
    companion object {
        const val SAMPLE_RATE = 16_000
        const val MAX_SECONDS = 120
        private const val CHANNEL = AudioFormat.CHANNEL_IN_MONO
        private const val ENCODING = AudioFormat.ENCODING_PCM_16BIT
        private const val MAX_PCM_BYTES = SAMPLE_RATE * 2 * MAX_SECONDS
    }

    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private val recording = AtomicBoolean(false)
    private val pcm = ByteArrayOutputStream()
    private var recorder: AudioRecord? = null
    private var captureThread: Thread? = null
    private var noiseSuppressor: NoiseSuppressor? = null

    fun start(onLevel: (Float) -> Unit, onLimitReached: () -> Unit) {
        if (!recording.compareAndSet(false, true)) return
        val minBuffer = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL, ENCODING)
        if (minBuffer <= 0) {
            recording.set(false)
            throw IllegalStateException("The microphone does not support 16 kHz recording on this device.")
        }
        val audio = try {
            AudioRecord(AudioSource.MIC, SAMPLE_RATE, CHANNEL, ENCODING, maxOf(minBuffer * 2, 4096))
        } catch (t: Throwable) {
            recording.set(false)
            throw t
        }
        if (audio.state != AudioRecord.STATE_INITIALIZED) {
            audio.release()
            recording.set(false)
            throw IllegalStateException("Android could not initialize the microphone. Check microphone access and retry.")
        }
        recorder = audio
        noiseSuppressor = runCatching {
            if (NoiseSuppressor.isAvailable()) NoiseSuppressor.create(audio.audioSessionId) else null
        }.getOrNull()
        synchronized(pcm) { pcm.reset() }
        try {
            audio.startRecording()
        } catch (t: Throwable) {
            release()
            recording.set(false)
            throw IllegalStateException("Android could not start microphone recording. Check microphone access and retry.", t)
        }
        captureThread = Thread({
            val samples = ShortArray(1024)
            var reachedLimit = false
            try {
                while (recording.get()) {
                    val count = audio.read(samples, 0, samples.size, AudioRecord.READ_BLOCKING)
                    if (count <= 0) {
                        if (count == AudioRecord.ERROR_DEAD_OBJECT) throw IllegalStateException("The microphone stopped unexpectedly. Retry dictation.")
                        continue
                    }
                    val chunk = ByteArray(count * 2)
                    var squareSum = 0.0
                    for (i in 0 until count) {
                        val sample = samples[i].toInt()
                        chunk[i * 2] = (sample and 0xff).toByte()
                        chunk[i * 2 + 1] = ((sample shr 8) and 0xff).toByte()
                        val normalized = sample / 32768.0
                        squareSum += normalized * normalized
                    }
                    synchronized(pcm) {
                        val remaining = MAX_PCM_BYTES - pcm.size()
                        if (remaining > 0) pcm.write(chunk, 0, minOf(remaining, chunk.size))
                        if (pcm.size() >= MAX_PCM_BYTES) {
                            reachedLimit = true
                            recording.set(false)
                        }
                    }
                    val rms = sqrt(squareSum / count.coerceAtLeast(1)).toFloat().coerceIn(0f, 1f)
                    mainHandler.post { onLevel(rms) }
                    if (reachedLimit) {
                        mainHandler.post(onLimitReached)
                        break
                    }
                }
            } catch (_: Throwable) {
                // The caller receives an empty/short recording and can retry without audio logs.
            } finally {
                mainHandler.post { onLevel(0f) }
            }
        }, "bq-dictation-recorder").apply { isDaemon = true; start() }
    }

    fun stop(): ByteArray {
        recording.set(false)
        val audio = recorder
        if (audio?.recordingState == AudioRecord.RECORDSTATE_RECORDING) runCatching { audio.stop() }
        runCatching { captureThread?.join(2000L) }
        captureThread = null
        release()
        val raw = synchronized(pcm) { pcm.toByteArray() }
        if (raw.isEmpty()) return ByteArray(0)
        return wavHeader(raw.size) + raw
    }

    fun cancel() {
        stop()
        synchronized(pcm) { pcm.reset() }
    }

    private fun release() {
        runCatching { noiseSuppressor?.release() }
        noiseSuppressor = null
        runCatching { recorder?.release() }
        recorder = null
    }

    private fun wavHeader(dataSize: Int): ByteArray {
        val header = ByteArray(44)
        fun putAscii(offset: Int, value: String) {
            value.toByteArray(Charsets.US_ASCII).copyInto(header, offset)
        }
        fun putInt(offset: Int, value: Int) {
            header[offset] = (value and 0xff).toByte()
            header[offset + 1] = ((value shr 8) and 0xff).toByte()
            header[offset + 2] = ((value shr 16) and 0xff).toByte()
            header[offset + 3] = ((value shr 24) and 0xff).toByte()
        }
        fun putShort(offset: Int, value: Int) {
            header[offset] = (value and 0xff).toByte()
            header[offset + 1] = ((value shr 8) and 0xff).toByte()
        }
        putAscii(0, "RIFF")
        putInt(4, dataSize + 36)
        putAscii(8, "WAVE")
        putAscii(12, "fmt ")
        putInt(16, 16)
        putShort(20, 1)
        putShort(22, 1)
        putInt(24, SAMPLE_RATE)
        putInt(28, SAMPLE_RATE * 2)
        putShort(32, 2)
        putShort(34, 16)
        putAscii(36, "data")
        putInt(40, dataSize)
        return header
    }
}
