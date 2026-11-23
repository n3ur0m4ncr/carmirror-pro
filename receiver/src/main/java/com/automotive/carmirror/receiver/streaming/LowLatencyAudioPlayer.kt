package com.automotive.carmirror.receiver.streaming

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Build
import android.util.Log
import com.automotive.carmirror.receiver.protocol.MirrorProtocol

/**
 * Reprodutor de Áudio PCM de Ultra-Baixa Latência para a Central Multimídia Allwinner T3L.
 * - Requisita AudioFocus de mídia (essencial para o MCU da multimídia automotiva liberar o áudio nos alto-falantes do carro).
 * - Tenta PERFORMANCE_MODE_LOW_LATENCY com fallback automático para PERFORMANCE_MODE_NONE caso o HAL de áudio da T3L não suporte fast-track.
 * - Escrita não-bloqueante para impedir acúmulo de atraso labial (lip-sync delay).
 */
class LowLatencyAudioPlayer(private val context: Context) {

    companion object {
        private const val TAG = "LowLatencyAudioPlayer"
    }

    private var audioTrack: AudioTrack? = null
    private var audioFocusRequest: AudioFocusRequest? = null
    private var isPlaying = false

    @Synchronized
    fun start() {
        if (isPlaying) return

        try {
            requestCarAudioFocus()

            val minBuf = AudioTrack.getMinBufferSize(
                MirrorProtocol.AUDIO_SAMPLE_RATE,
                AudioFormat.CHANNEL_OUT_STEREO,
                AudioFormat.ENCODING_PCM_16BIT
            )

            // Buffer de ~80ms: baixa latência mantendo estabilidade no HAL de áudio da Allwinner T3L
            val targetBufferBytes = maxOf(minBuf * 2, 1764 * 8)

            val attrs = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .build()

            val format = AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(MirrorProtocol.AUDIO_SAMPLE_RATE)
                .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                .build()

            var track: AudioTrack? = null
            try {
                track = AudioTrack.Builder()
                    .setAudioAttributes(attrs)
                    .setAudioFormat(format)
                    .setBufferSizeInBytes(targetBufferBytes)
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
                    .build()
                if (track.state != AudioTrack.STATE_INITIALIZED) {
                    track.release()
                    track = null
                }
            } catch (e: Exception) {
                Log.w(TAG, "HAL T3L não suportou LOW_LATENCY, usando modo padrão: ${e.message}")
                track = null
            }

            if (track == null) {
                track = AudioTrack.Builder()
                    .setAudioAttributes(attrs)
                    .setAudioFormat(format)
                    .setBufferSizeInBytes(targetBufferBytes)
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_NONE)
                    .build()
            }

            if (track.state != AudioTrack.STATE_INITIALIZED) {
                Log.e(TAG, "AudioTrack falhou ao inicializar na T3L")
                track.release()
                return
            }

            track.play()
            audioTrack = track
            isPlaying = true
            Log.i(TAG, "AudioTrack iniciado na T3L (buffer=$targetBufferBytes bytes)")
        } catch (e: Exception) {
            Log.e(TAG, "Falha ao inicializar AudioTrack na T3L: ${e.message}")
        }
    }

    fun writePcmChunk(buffer: ByteArray, offset: Int, length: Int) {
        val track = audioTrack ?: return
        if (!isPlaying) return
        try {
            track.write(buffer, offset, length, AudioTrack.WRITE_NON_BLOCKING)
        } catch (_: Exception) {}
    }

    @Suppress("DEPRECATION")
    private fun requestCarAudioFocus() {
        try {
            val am = context.applicationContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                            .build()
                    )
                    .setAcceptsDelayedFocusGain(true)
                    .setOnAudioFocusChangeListener { }
                    .build()
                audioFocusRequest = req
                am.requestAudioFocus(req)
            } else {
                am.requestAudioFocus(null, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Aviso ao requisitar AudioFocus automotivo: ${e.message}")
        }
    }

    @Suppress("DEPRECATION")
    private fun abandonCarAudioFocus() {
        try {
            val am = context.applicationContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                audioFocusRequest?.let { am.abandonAudioFocusRequest(it) }
                audioFocusRequest = null
            } else {
                am.abandonAudioFocus(null)
            }
        } catch (_: Exception) {}
    }

    @Synchronized
    fun stop() {
        isPlaying = false
        try {
            audioTrack?.pause()
            audioTrack?.flush()
            audioTrack?.stop()
        } catch (_: Exception) {}
        try {
            audioTrack?.release()
        } catch (_: Exception) {}
        audioTrack = null
        abandonCarAudioFocus()
    }
}
