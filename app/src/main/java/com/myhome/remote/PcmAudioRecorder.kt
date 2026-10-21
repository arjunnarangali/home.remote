package com.myhome.remote

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.util.concurrent.atomic.AtomicBoolean

/** Captures short-lived 16 kHz mono PCM in memory; audio is never written to disk. */
class PcmAudioRecorder(context: Context) {
    private val appContext = context.applicationContext
    private val mutex = Mutex()
    private val recording = AtomicBoolean(false)
    private val readerScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val cleanupScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile
    private var recorder: AudioRecord? = null
    @Volatile
    private var reader: Job? = null
    private val pcm = ByteArrayOutputStream()

    suspend fun start() = withContext(Dispatchers.IO) {
        mutex.withLock {
            stopInternal()
            synchronized(pcm) { pcm.reset() }
            val minimum = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL, ENCODING)
            check(minimum > 0) { "Microphone is unavailable" }
            val audioRecord = createAudioRecord(maxOf(minimum, SAMPLE_RATE / 2))
            try {
                check(audioRecord.state == AudioRecord.STATE_INITIALIZED) { "Could not initialize microphone" }
                audioRecord.startRecording()
                recorder = audioRecord
                recording.set(true)
                reader = readerScope.launch {
                    val buffer = ByteArray(4096)
                    while (recording.get()) {
                        val size = audioRecord.read(buffer, 0, buffer.size, AudioRecord.READ_BLOCKING)
                        if (size > 0) synchronized(pcm) { pcm.write(buffer, 0, size) }
                        else if (size < 0) recording.set(false)
                        else kotlinx.coroutines.delay(10)
                    }
                }
            } catch (error: Throwable) {
                recording.set(false)
                audioRecord.release()
                throw error
            }
        }
    }

    suspend fun stop(): ByteArray = withContext(Dispatchers.IO) {
        mutex.withLock {
            stopInternal()
            val bytes = synchronized(pcm) { pcm.toByteArray() }
            check(bytes.size >= SAMPLE_RATE * 2 * MIN_SECONDS) { "Nothing captured" }
            PcmWav.encode(bytes, SAMPLE_RATE)
        }
    }

    suspend fun cancel() = withContext(Dispatchers.IO) {
        mutex.withLock {
            stopInternal()
            synchronized(pcm) { pcm.reset() }
        }
    }

    fun cancelNow() {
        recording.set(false)
        val audioRecord = recorder
        val readJob = reader
        recorder = null
        reader = null
        if (audioRecord == null) return
        runCatching { audioRecord.stop() }
        cleanupScope.launch {
            withContext(NonCancellable) {
                readJob?.cancelAndJoin()
                runCatching { audioRecord.release() }
                synchronized(pcm) { pcm.reset() }
            }
        }
    }

    private suspend fun stopInternal() {
        recording.set(false)
        val audioRecord = recorder
        val readJob = reader
        recorder = null
        reader = null
        if (audioRecord != null) runCatching { audioRecord.stop() }
        withContext(NonCancellable) {
            readJob?.join()
            audioRecord?.let { runCatching { it.release() } }
        }
    }

    @SuppressLint("MissingPermission") // Guarded below; the user can revoke mic access between UI checks and recording.
    private fun createAudioRecord(bufferBytes: Int): AudioRecord {
        check(ContextCompat.checkSelfPermission(appContext, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            "Microphone permission is required for Ask Rocky"
        }
        return AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, SAMPLE_RATE, CHANNEL, ENCODING, bufferBytes)
    }

    companion object {
        const val SAMPLE_RATE = 16_000
        private const val MIN_SECONDS = 0.35
        private const val CHANNEL = AudioFormat.CHANNEL_IN_MONO
        private const val ENCODING = AudioFormat.ENCODING_PCM_16BIT
    }
}

object PcmWav {
    fun encode(pcm: ByteArray, sampleRate: Int): ByteArray {
        require(sampleRate > 0) { "Sample rate must be positive" }
        require(pcm.size % 2 == 0) { "16-bit PCM data must have an even byte count" }
        val header = ByteArray(44)
        fun ascii(offset: Int, value: String) = value.forEachIndexed { index, char -> header[offset + index] = char.code.toByte() }
        fun short(offset: Int, value: Int) {
            header[offset] = (value and 0xff).toByte()
            header[offset + 1] = ((value ushr 8) and 0xff).toByte()
        }
        fun int(offset: Int, value: Int) {
            for (i in 0..3) header[offset + i] = ((value ushr (8 * i)) and 0xff).toByte()
        }
        ascii(0, "RIFF"); int(4, 36 + pcm.size); ascii(8, "WAVE")
        ascii(12, "fmt "); int(16, 16); short(20, 1); short(22, 1)
        int(24, sampleRate); int(28, sampleRate * 2); short(32, 2); short(34, 16)
        ascii(36, "data"); int(40, pcm.size)
        return header + pcm
    }
}
