package com.automotive.carmirror.sender.streaming

import android.annotation.SuppressLint
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.projection.MediaProjection
import android.os.Build
import android.util.Log
import com.automotive.carmirror.sender.protocol.MirrorProtocol
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs

/**
 * Capturador de Áudio Interno do Android 10–15+ via AudioPlaybackCaptureConfiguration.
 * - Captura áudio de mídia/navegação/jogos em blocos PCM 16-bit 44.1kHz Stereo de 10ms (lip-sync < 15ms).
 * - Inclui Pré-Amplificador Digital Adaptativo (AGC + Soft Limiter) sem alocações no heap:
 *   permite manter o alto-falante físico do celular no volume mínimo inaudível (nível 1)
 *   enquanto entrega 100% de volume limpo e sem distorção nos alto-falantes da multimídia T3L!
 */
class InternalAudioCapturer(
    private val mediaProjection: MediaProjection,
    private val onAudioChunk: (ptsUs: Long, pcmData: ByteArray, length: Int) -> Unit
) {
    companion object {
        private const val TAG = "InternalAudioCapturer"
        private const val TARGET_PEAK = 24_500f
        private const val SOFT_KNEE = 28_000f
        private const val MAX_PEAK = 32_760f
        private const val NOISE_GATE_THRESHOLD = 20
        private const val MAX_AGC_GAIN = 18.0f
    }

    private var audioRecord: AudioRecord? = null
    private var captureThread: Thread? = null
    private val isCapturing = AtomicBoolean(false)

    @Volatile
    var agcBoostEnabled: Boolean = true

    private var currentGain: Float = 1.0f

    @SuppressLint("MissingPermission")
    fun start() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            Log.w(TAG, "AudioPlaybackCapture requer Android 10 (API 29)+")
            return
        }
        if (isCapturing.get()) return

        try {
            val captureConfig = AudioPlaybackCaptureConfiguration.Builder(mediaProjection)
                .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                .addMatchingUsage(AudioAttributes.USAGE_GAME)
                .addMatchingUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                .build()

            val audioFormat = AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(MirrorProtocol.AUDIO_SAMPLE_RATE)
                .setChannelMask(AudioFormat.CHANNEL_IN_STEREO)
                .build()

            val minBufferSize = AudioRecord.getMinBufferSize(
                MirrorProtocol.AUDIO_SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_STEREO,
                AudioFormat.ENCODING_PCM_16BIT
            )

            // Janela de leitura curta (~10ms = 1764 bytes em 44.1kHz Stereo 16-bit)
            val chunkBytes = 1764
            val recordBufferSize = maxOf(minBufferSize, chunkBytes * 4)

            val recorder = AudioRecord.Builder()
                .setAudioFormat(audioFormat)
                .setBufferSizeInBytes(recordBufferSize)
                .setAudioPlaybackCaptureConfig(captureConfig)
                .build()

            if (recorder.state != AudioRecord.STATE_INITIALIZED) {
                Log.e(TAG, "AudioRecord falhou ao inicializar")
                recorder.release()
                return
            }

            recorder.startRecording()
            audioRecord = recorder
            currentGain = 1.0f
            isCapturing.set(true)

            captureThread = Thread({
                val pcmBuffer = ByteArray(chunkBytes)
                while (isCapturing.get()) {
                    val read = recorder.read(pcmBuffer, 0, pcmBuffer.size)
                    if (read > 0) {
                        if (agcBoostEnabled) {
                            applyAdaptiveAgcInPlace(pcmBuffer, read)
                        }
                        val ptsUs = System.nanoTime() / 1000L
                        onAudioChunk(ptsUs, pcmBuffer, read)
                    }
                }
            }, "InternalAudioCaptureThread").apply {
                priority = Thread.MAX_PRIORITY
                start()
            }

            Log.i(TAG, "Captura de áudio interno iniciada (44.1kHz Stereo PCM 16-bit + AGC)")
        } catch (e: Exception) {
            Log.e(TAG, "Erro ao iniciar captura de áudio interno: ${e.message}", e)
        }
    }

    /**
     * Normaliza o áudio PCM 16-bit Little-Endian em tempo real quando o alto-falante do celular
     * está silenciado no nível mínimo (1), com ataque rápido anti-clipping e soft limiter.
     */
    private fun applyAdaptiveAgcInPlace(buffer: ByteArray, length: Int) {
        val sampleBytes = length and 0x7FFFFFFE // Garante par de bytes (16-bit)
        if (sampleBytes <= 0) return

        var peakAbs = 0
        var i = 0
        while (i < sampleBytes) {
            val lo = buffer[i].toInt() and 0xFF
            val hi = buffer[i + 1].toInt()
            val sample = (hi shl 8) or lo
            val absVal = abs(sample)
            if (absVal > peakAbs) {
                peakAbs = absVal
            }
            i += 2
        }

        // Noise gate: não amplifica silêncio puro
        if (peakAbs < NOISE_GATE_THRESHOLD) {
            return
        }

        val desiredGain = (TARGET_PEAK / peakAbs.toFloat()).coerceIn(1.0f, MAX_AGC_GAIN)

        // Ataque rápido se o som subir repentinamente; subida suave se o som estiver baixo
        currentGain = if (desiredGain < currentGain) {
            currentGain * 0.30f + desiredGain * 0.70f
        } else {
            currentGain * 0.88f + desiredGain * 0.12f
        }

        if (currentGain <= 1.02f) return

        val gain = currentGain
        i = 0
        while (i < sampleBytes) {
            val lo = buffer[i].toInt() and 0xFF
            val hi = buffer[i + 1].toInt()
            val sample = (hi shl 8) or lo

            var boosted = sample * gain
            // Soft Limiter acima de SOFT_KNEE para evitar distorção em batidas graves
            val absBoosted = abs(boosted)
            if (absBoosted > SOFT_KNEE) {
                val excess = absBoosted - SOFT_KNEE
                val range = MAX_PEAK - SOFT_KNEE
                val compressed = SOFT_KNEE + range * (excess / (excess + range))
                boosted = if (boosted >= 0f) compressed else -compressed
            }

            val outSample = boosted.toInt().coerceIn(-32768, 32767)
            buffer[i] = (outSample and 0xFF).toByte()
            buffer[i + 1] = ((outSample ushr 8) and 0xFF).toByte()
            i += 2
        }
    }

    fun stop() {
        isCapturing.set(false)
        try {
            captureThread?.join(300L)
        } catch (_: InterruptedException) {}
        captureThread = null

        try {
            audioRecord?.stop()
        } catch (_: Exception) {}
        try {
            audioRecord?.release()
        } catch (_: Exception) {}
        audioRecord = null
    }
}
