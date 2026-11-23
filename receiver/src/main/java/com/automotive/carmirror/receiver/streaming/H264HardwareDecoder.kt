package com.automotive.carmirror.receiver.streaming

import android.media.MediaCodec
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.Build
import android.util.Log
import android.view.Surface
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Decodificador H.264 estritamente via Hardware para a placa Allwinner T3L (ARM Cortex-A7 32-bit, 2GB RAM).
 * - Seleciona explicitamente o decoder HW (ex: OMX.allwinner.video.decoder.avc / c2.allwinner.avc.decoder).
 * - Zero alocações de memória por quadro: escreve diretamente do buffer de rede pré-alocado para o ByteBuffer nativo do MediaCodec.
 * - Drenagem assíncrona com renderização imediata na SurfaceView (latency = 0).
 */
class H264HardwareDecoder(
    private val onRequestKeyframe: () -> Unit
) {
    companion object {
        private const val TAG = "H264HardwareDecoder"
        private const val MIME_TYPE = MediaFormat.MIMETYPE_VIDEO_AVC
    }

    private var decoder: MediaCodec? = null
    private var outputSurface: Surface? = null
    private var drainThread: Thread? = null
    private val isConfigured = AtomicBoolean(false)
    private var currentWidth: Int = 1024
    private var currentHeight: Int = 600
    private var consecutiveErrors = 0

    @Synchronized
    fun attachSurface(surface: Surface, width: Int = 1024, height: Int = 600) {
        outputSurface = surface
        configureDecoderInternal(surface, width, height, null)
    }

    @Synchronized
    fun updateResolutionAndConfig(width: Int, height: Int, spsPpsBytes: ByteArray?) {
        val surface = outputSurface ?: return
        if (!isConfigured.get() || width != currentWidth || height != currentHeight) {
            configureDecoderInternal(surface, width, height, spsPpsBytes)
        } else if (spsPpsBytes != null) {
            feedFrame(
                data = spsPpsBytes,
                offset = 0,
                length = spsPpsBytes.size,
                ptsUs = 0L,
                flags = MediaCodec.BUFFER_FLAG_CODEC_CONFIG
            )
        }
    }

    @Synchronized
    private fun configureDecoderInternal(
        surface: Surface,
        width: Int,
        height: Int,
        spsPpsBytes: ByteArray?
    ) {
        releaseDecoderOnly()
        if (!surface.isValid) return

        currentWidth = width
        currentHeight = height

        try {
            val hwCodecName = findHardwareAvcDecoderName()
            val codec = if (hwCodecName != null) {
                Log.i(TAG, "Selecionado Decoder HW nativo da T3L: $hwCodecName")
                MediaCodec.createByCodecName(hwCodecName)
            } else {
                MediaCodec.createDecoderByType(MIME_TYPE)
            }

            val format = MediaFormat.createVideoFormat(MIME_TYPE, width, height).apply {
                // Otimizações de Baixa Latência para Android 12 (API 31) na placa T3L
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
                }
                setInteger(MediaFormat.KEY_PRIORITY, 0) // 0 = Realtime
                setInteger(MediaFormat.KEY_OPERATING_RATE, Short.MAX_VALUE.toInt())
                // Evita realocação na VPU CedarX da T3L quando o stream alternar entre 1024x600 e 1280x720
                setInteger(MediaFormat.KEY_MAX_WIDTH, 1280)
                setInteger(MediaFormat.KEY_MAX_HEIGHT, 720)
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 512 * 1024)

                if (spsPpsBytes != null && spsPpsBytes.isNotEmpty()) {
                    setByteBuffer("csd-0", ByteBuffer.wrap(spsPpsBytes))
                }
            }

            try {
                codec.configure(format, surface, null, 0)
            } catch (e: Exception) {
                Log.w(TAG, "Fallback para MediaFormat enxuto na VPU Allwinner: ${e.message}")
                val minimalFormat = MediaFormat.createVideoFormat(MIME_TYPE, width, height).apply {
                    if (spsPpsBytes != null && spsPpsBytes.isNotEmpty()) {
                        setByteBuffer("csd-0", ByteBuffer.wrap(spsPpsBytes))
                    }
                }
                codec.configure(minimalFormat, surface, null, 0)
            }

            codec.start()
            decoder = codec
            isConfigured.set(true)
            consecutiveErrors = 0

            drainThread = Thread({ drainOutputLoop(codec) }, "T3LDecoderDrainThread").apply {
                priority = Thread.MAX_PRIORITY
                start()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Falha ao inicializar H264HardwareDecoder: ${e.message}", e)
            isConfigured.set(false)
        }
    }

    /**
     * Alimenta o decoder de hardware com zero alocação de memória (Zero-Copy no Heap Kotlin).
     */
    fun feedFrame(
        data: ByteArray,
        offset: Int,
        length: Int,
        ptsUs: Long,
        flags: Int
    ) {
        val codec = decoder
        if (!isConfigured.get() || codec == null) return

        try {
            val inIndex = codec.dequeueInputBuffer(8_000L)
            if (inIndex >= 0) {
                val inputBuffer: ByteBuffer? = codec.getInputBuffer(inIndex)
                if (inputBuffer != null) {
                    inputBuffer.clear()
                    val safeLen = minOf(length, inputBuffer.remaining())
                    inputBuffer.put(data, offset, safeLen)
                    codec.queueInputBuffer(inIndex, 0, safeLen, ptsUs, flags)
                    consecutiveErrors = 0
                }
            } else {
                // Input buffers cheios momentaneamente; solicita IDR se persistir
                consecutiveErrors++
                if (consecutiveErrors > 15) {
                    consecutiveErrors = 0
                    onRequestKeyframe()
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Erro ao enfileirar NAL unit no decoder T3L: ${e.message}")
            consecutiveErrors++
            if (consecutiveErrors > 5) {
                onRequestKeyframe()
            }
        }
    }

    private fun drainOutputLoop(codec: MediaCodec) {
        val bufferInfo = MediaCodec.BufferInfo()
        while (isConfigured.get()) {
            try {
                val outIndex = codec.dequeueOutputBuffer(bufferInfo, 10_000L)
                if (outIndex >= 0) {
                    // Renderiza imediatamente na SurfaceView sem aguardar relógio de PTS
                    codec.releaseOutputBuffer(outIndex, bufferInfo.size > 0)
                }
            } catch (_: IllegalStateException) {
                break
            } catch (e: Exception) {
                if (isConfigured.get()) {
                    Log.w(TAG, "Aviso na drenagem do decoder T3L: ${e.message}")
                }
            }
        }
    }

    /**
     * Prioriza o decodificador por hardware do SoC Allwinner T3L (CedarX / OMX.allwinner)
     * para manter o uso de CPU Cortex-A7 abaixo de 15%.
     */
    private fun findHardwareAvcDecoderName(): String? {
        try {
            val codecList = MediaCodecList(MediaCodecList.REGULAR_CODECS)
            var fallbackHwCodec: String? = null

            for (info in codecList.codecInfos) {
                if (info.isEncoder) continue
                val supportsAvc = info.supportedTypes.any { it.equals(MIME_TYPE, ignoreCase = true) }
                if (!supportsAvc) continue

                val name = info.name.lowercase()
                val isSoftware = name.startsWith("omx.google.") ||
                    name.startsWith("c2.android.") ||
                    name.contains(".sw.") ||
                    (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && info.isSoftwareOnly)

                if (!isSoftware) {
                    if (name.contains("allwinner") || name.contains("cedar") || name.contains("sunxi")) {
                        return info.name
                    }
                    if (fallbackHwCodec == null) {
                        fallbackHwCodec = info.name
                    }
                }
            }
            return fallbackHwCodec
        } catch (_: Exception) {
            return null
        }
    }

    @Synchronized
    private fun releaseDecoderOnly() {
        isConfigured.set(false)
        try {
            drainThread?.join(250L)
        } catch (_: InterruptedException) {}
        drainThread = null

        try {
            decoder?.stop()
        } catch (_: Exception) {}
        try {
            decoder?.release()
        } catch (_: Exception) {}
        decoder = null
    }

    @Synchronized
    fun release() {
        releaseDecoderOnly()
        outputSurface = null
    }
}
