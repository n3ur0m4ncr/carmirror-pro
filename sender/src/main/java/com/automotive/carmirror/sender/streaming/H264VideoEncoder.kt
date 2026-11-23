package com.automotive.carmirror.sender.streaming

import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.projection.MediaProjection
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.Surface
import com.automotive.carmirror.sender.protocol.MirrorProtocol
import com.automotive.carmirror.sender.protocol.StreamProfile
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Encoder H.264 (AVC) de Ultra-Baixa Latência acoplado ao VirtualDisplay do MediaProjection.
 * Otimizado especificamente para decodificação por hardware no chip Allwinner T3L (CedarX / ARMv7):
 * - Baseline Profile com 0 B-Frames (elimina fila de reordenação no decoder)
 * - CBR (Constant Bitrate) e KEY_LATENCY = 0
 * - Cache automático de SPS/PPS (CSD-0) para inicialização instantânea da tela no carro
 */
class H264VideoEncoder(
    private val mediaProjection: MediaProjection,
    private val onEncodedPacket: (packetType: Byte, ptsUs: Long, data: ByteArray, length: Int) -> Unit
) {
    companion object {
        private const val TAG = "H264VideoEncoder"
        private const val MIME_TYPE = MediaFormat.MIMETYPE_VIDEO_AVC
    }

    private var mediaCodec: MediaCodec? = null
    private var inputSurface: Surface? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var encoderThread: Thread? = null
    private val isRunning = AtomicBoolean(false)

    @Volatile
    var currentProfile: StreamProfile = StreamProfile.PROFILE_T3L_NATIVE
        private set

    // Cache do último cabeçalho SPS/PPS (BUFFER_FLAG_CODEC_CONFIG) para envio imediato a novos clientes
    @Volatile
    var cachedSpsPpsConfig: ByteArray? = null
        private set

    @Synchronized
    fun start(profile: StreamProfile = currentProfile) {
        if (isRunning.get()) {
            stop()
        }
        currentProfile = profile
        isRunning.set(true)

        val format = MediaFormat.createVideoFormat(MIME_TYPE, profile.width, profile.height).apply {
            setInteger(
                MediaFormat.KEY_COLOR_FORMAT,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface
            )
            setInteger(MediaFormat.KEY_BIT_RATE, profile.bitrateBps)
            setInteger(MediaFormat.KEY_FRAME_RATE, profile.fps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1) // 1 segundo entre I-Frames
            setInteger(
                MediaFormat.KEY_BITRATE_MODE,
                MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR
            )
            // Baseline Profile sem B-Frames: essencial para o decoder HW Allwinner T3L
            setInteger(
                MediaFormat.KEY_PROFILE,
                MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline
            )
            setInteger(
                MediaFormat.KEY_LEVEL,
                if (profile.fps > 30 || profile.width > 1024)
                    MediaCodecInfo.CodecProfileLevel.AVCLevel32
                else
                    MediaCodecInfo.CodecProfileLevel.AVCLevel31
            )
            setInteger(MediaFormat.KEY_LATENCY, 0)
            setInteger(MediaFormat.KEY_PRIORITY, 0) // 0 = Realtime priority
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                setInteger(MediaFormat.KEY_MAX_B_FRAMES, 0)
            }
            // Repetir quadro anterior após 100ms caso a tela fique estática
            setLong(MediaFormat.KEY_REPEAT_PREVIOUS_FRAME_AFTER, 100_000L)
        }

        val codec = MediaCodec.createEncoderByType(MIME_TYPE)
        try {
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        } catch (e: Exception) {
            Log.w(TAG, "Fallback para configuração de encoder sem flags restritas: ${e.message}")
            val fallbackFormat = MediaFormat.createVideoFormat(MIME_TYPE, profile.width, profile.height).apply {
                setInteger(
                    MediaFormat.KEY_COLOR_FORMAT,
                    MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface
                )
                setInteger(MediaFormat.KEY_BIT_RATE, profile.bitrateBps)
                setInteger(MediaFormat.KEY_FRAME_RATE, profile.fps)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            }
            codec.configure(fallbackFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        }

        val surface = codec.createInputSurface()
        codec.start()

        val vDisplay = mediaProjection.createVirtualDisplay(
            "CarMirrorVirtualDisplay",
            profile.width,
            profile.height,
            profile.dpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR or DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC,
            surface,
            null,
            null
        )

        mediaCodec = codec
        inputSurface = surface
        virtualDisplay = vDisplay

        encoderThread = Thread({ drainEncoderLoop(codec) }, "H264EncoderDrainThread").apply {
            priority = Thread.MAX_PRIORITY
            start()
        }

        Log.i(TAG, "Encoder H.264 iniciado: ${profile.width}x${profile.height} @ ${profile.fps}fps")
    }

    /**
     * Força a geração imediata de um quadro IDR (Keyframe) pelo encoder de hardware.
     */
    fun requestInstantIdrFrame() {
        try {
            val params = Bundle().apply {
                putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0)
            }
            mediaCodec?.setParameters(params)
        } catch (e: Exception) {
            Log.w(TAG, "Falha ao solicitar Sync Frame: ${e.message}")
        }
    }

    /**
     * Ajusta dinamicamente o bitrate em tempo real sem interromper o VirtualDisplay.
     */
    fun updateBitrate(newBitrateBps: Int) {
        try {
            val params = Bundle().apply {
                putInt(MediaCodec.PARAMETER_KEY_VIDEO_BITRATE, newBitrateBps)
            }
            mediaCodec?.setParameters(params)
        } catch (e: Exception) {
            Log.w(TAG, "Erro ao ajustar bitrate dinâmico: ${e.message}")
        }
    }

    /**
     * Reconfigura resolução e FPS em tempo real (ex: alternando entre 1024x600 e 1280x720).
     */
    @Synchronized
    fun reconfigureProfile(newProfile: StreamProfile) {
        Log.i(TAG, "Reconfigurando encoder para ${newProfile.label}")
        stop()
        start(newProfile)
    }

    private fun drainEncoderLoop(codec: MediaCodec) {
        val bufferInfo = MediaCodec.BufferInfo()
        var reusableBuffer = ByteArray(256 * 1024)

        while (isRunning.get()) {
            try {
                val outIndex = codec.dequeueOutputBuffer(bufferInfo, 10_000L)
                if (outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    val outFormat = codec.outputFormat
                    extractSpsPpsFromFormat(outFormat)
                } else if (outIndex >= 0) {
                    val encodedData: ByteBuffer? = codec.getOutputBuffer(outIndex)
                    if (encodedData != null && bufferInfo.size > 0) {
                        encodedData.position(bufferInfo.offset)
                        encodedData.limit(bufferInfo.offset + bufferInfo.size)

                        if (bufferInfo.size > reusableBuffer.size) {
                            reusableBuffer = ByteArray(bufferInfo.size + 32 * 1024)
                        }
                        encodedData.get(reusableBuffer, 0, bufferInfo.size)

                        val isConfig = (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0
                        val isKeyFrame = (bufferInfo.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0

                        if (isConfig) {
                            val configCopy = reusableBuffer.copyOfRange(0, bufferInfo.size)
                            cachedSpsPpsConfig = configCopy
                            onEncodedPacket(
                                MirrorProtocol.TYPE_VIDEO_CONFIG,
                                bufferInfo.presentationTimeUs,
                                configCopy,
                                configCopy.size
                            )
                        } else {
                            val packetType = if (isKeyFrame) {
                                MirrorProtocol.TYPE_VIDEO_KEYFRAME
                            } else {
                                MirrorProtocol.TYPE_VIDEO_FRAME
                            }
                            onEncodedPacket(
                                packetType,
                                bufferInfo.presentationTimeUs,
                                reusableBuffer,
                                bufferInfo.size
                            )
                        }
                    }
                    codec.releaseOutputBuffer(outIndex, false)
                }
            } catch (e: IllegalStateException) {
                if (isRunning.get()) {
                    Log.e(TAG, "MediaCodec estado inválido: ${e.message}")
                }
                break
            } catch (e: Exception) {
                if (isRunning.get()) {
                    Log.e(TAG, "Erro no loop do encoder: ${e.message}", e)
                }
            }
        }
    }

    private fun extractSpsPpsFromFormat(format: MediaFormat) {
        try {
            val csd0 = format.getByteBuffer("csd-0") ?: return
            val csd1 = format.getByteBuffer("csd-1")
            val spsBytes = ByteArray(csd0.remaining())
            csd0.get(spsBytes)
            csd0.rewind()

            val combined = if (csd1 != null) {
                val ppsBytes = ByteArray(csd1.remaining())
                csd1.get(ppsBytes)
                csd1.rewind()
                spsBytes + ppsBytes
            } else {
                spsBytes
            }
            cachedSpsPpsConfig = combined
            onEncodedPacket(MirrorProtocol.TYPE_VIDEO_CONFIG, 0L, combined, combined.size)
        } catch (e: Exception) {
            Log.w(TAG, "Falha ao extrair CSD do MediaFormat: ${e.message}")
        }
    }

    @Synchronized
    fun stop() {
        isRunning.set(false)
        try {
            encoderThread?.join(400L)
        } catch (_: InterruptedException) {}
        encoderThread = null

        try {
            virtualDisplay?.release()
        } catch (_: Exception) {}
        virtualDisplay = null

        try {
            inputSurface?.release()
        } catch (_: Exception) {}
        inputSurface = null

        try {
            mediaCodec?.stop()
        } catch (_: Exception) {}
        try {
            mediaCodec?.release()
        } catch (_: Exception) {}
        mediaCodec = null
    }
}
