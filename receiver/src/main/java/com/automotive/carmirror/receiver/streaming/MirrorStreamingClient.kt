package com.automotive.carmirror.receiver.streaming

import android.media.MediaCodec
import android.util.Log
import com.automotive.carmirror.receiver.protocol.MirrorProtocol
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Cliente TCP Full-Duplex de Baixa Latência para a Central Multimídia Allwinner T3L.
 * - Aloca um único buffer reutilizável (Zero-GC Heap Allocation por quadro) para preservar os ~1.1 GB livres da T3L.
 * - Reconexão automática transparente caso o sinal Wi-Fi oscile.
 * - Canal de retorno (Upstream) assíncrono para Touchback imediato.
 */
class MirrorStreamingClient(
    private val videoDecoder: H264HardwareDecoder,
    private val audioPlayer: LowLatencyAudioPlayer,
    private val onConnectionStateChanged: (connected: Boolean, statusMessage: String) -> Unit,
    private val onStreamResolutionChanged: (width: Int, height: Int, fps: Int) -> Unit,
    private val onRemoteStatusChanged: (touchbackEnabled: Boolean, audioMuted: Boolean, dimPhoneScreen: Boolean) -> Unit
) {
    companion object {
        private const val TAG = "MirrorStreamingClient"
        private const val CONNECT_TIMEOUT_MS = 4000
        private const val READ_TIMEOUT_MS = 10000
    }

    private class UpstreamCommand(
        val cmdType: Byte,
        val action: Byte,
        val float1: Float,
        val float2: Float,
        val intExtra: Int
    )

    private val isActive = AtomicBoolean(false)
    private val isConnected = AtomicBoolean(false)
    private var currentSocket: Socket? = null
    private var connectionThread: Thread? = null
    private var writerThread: Thread? = null
    private val upstreamQueue = LinkedBlockingQueue<UpstreamCommand>(128)

    @Volatile
    private var targetHost: String = ""

    @Volatile
    private var targetPort: Int = MirrorProtocol.TCP_STREAM_PORT

    @Volatile
    private var streamWidth: Int = 1024

    @Volatile
    private var streamHeight: Int = 600

    fun isSessionActive(): Boolean = isActive.get()

    fun isConnectedNow(): Boolean = isConnected.get()

    @Synchronized
    fun connectWithAutoReconnect(host: String, port: Int = MirrorProtocol.TCP_STREAM_PORT) {
        val cleanHost = host.trim()
        if (cleanHost.isEmpty()) return

        // CORREÇÃO CRÍTICA: Se já estamos ativos/conectados no mesmo IP e porta,
        // NÃO fecha o socket atual quando novos beacons UDP de descoberta chegarem!
        if (isActive.get()) {
            if (targetHost == cleanHost && targetPort == port) {
                return
            }
            // Só força reconexão se o IP do celular realmente mudou
            targetHost = cleanHost
            targetPort = port
            closeCurrentSocket()
            return
        }

        targetHost = cleanHost
        targetPort = port
        isActive.set(true)

        connectionThread = Thread({
            connectionSupervisorLoop()
        }, "T3LStreamSupervisorThread").apply {
            priority = Thread.MAX_PRIORITY
            start()
        }
    }

    private fun connectionSupervisorLoop() {
        // Buffer único pré-alocado na inicialização: zero alocações no GC por quadro H.264/PCM!
        var reusableReadBuffer = ByteArray(512 * 1024)

        while (isActive.get()) {
            val host = targetHost
            val port = targetPort
            if (host.isBlank()) {
                Thread.sleep(500L)
                continue
            }

            try {
                onConnectionStateChanged(false, "Conectando ao celular ($host:$port)...")
                val socket = Socket()
                socket.tcpNoDelay = true
                socket.trafficClass = 0x10 // IPTOS_LOWDELAY
                socket.receiveBufferSize = 256 * 1024
                socket.sendBufferSize = 32 * 1024
                socket.soTimeout = READ_TIMEOUT_MS
                socket.connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS)

                currentSocket = socket
                upstreamQueue.clear()
                isConnected.set(true)
                audioPlayer.start()
                onConnectionStateChanged(true, "Espelhando ($host)")

                // Inicia thread de envio de Touchback (Upstream: T3L -> Celular)
                val outStream = DataOutputStream(BufferedOutputStream(socket.getOutputStream(), 4096))
                writerThread = Thread({
                    upstreamWriterLoop(socket, outStream)
                }, "T3LTouchbackWriterThread").apply {
                    priority = Thread.MAX_PRIORITY
                    start()
                }

                // Solicita SPS/PPS + IDR Keyframe imediato ao conectar
                requestKeyframe()

                // Loop de leitura Downstream (Celular -> T3L)
                val inStream = DataInputStream(BufferedInputStream(socket.getInputStream(), 64 * 1024))
                while (isActive.get() && isConnected.get() && !socket.isClosed) {
                    val packetType = inStream.readByte()
                    val ptsUs = inStream.readLong()
                    val payloadLen = inStream.readInt()

                    if (payloadLen < 0 || payloadLen > 4 * 1024 * 1024) {
                        throw IllegalStateException("Tamanho de pacote inválido: $payloadLen")
                    }

                    if (payloadLen > reusableReadBuffer.size) {
                        reusableReadBuffer = ByteArray(payloadLen + 64 * 1024)
                    }

                    if (payloadLen > 0) {
                        inStream.readFully(reusableReadBuffer, 0, payloadLen)
                    }

                    when (packetType) {
                        MirrorProtocol.TYPE_VIDEO_CONFIG -> {
                            val spsPpsCopy = reusableReadBuffer.copyOfRange(0, payloadLen)
                            videoDecoder.updateResolutionAndConfig(streamWidth, streamHeight, spsPpsCopy)
                        }

                        MirrorProtocol.TYPE_VIDEO_KEYFRAME -> {
                            videoDecoder.feedFrame(
                                data = reusableReadBuffer,
                                offset = 0,
                                length = payloadLen,
                                ptsUs = ptsUs,
                                flags = MediaCodec.BUFFER_FLAG_KEY_FRAME
                            )
                        }

                        MirrorProtocol.TYPE_VIDEO_FRAME -> {
                            videoDecoder.feedFrame(
                                data = reusableReadBuffer,
                                offset = 0,
                                length = payloadLen,
                                ptsUs = ptsUs,
                                flags = 0
                            )
                        }

                        MirrorProtocol.TYPE_AUDIO_PCM -> {
                            audioPlayer.writePcmChunk(reusableReadBuffer, 0, payloadLen)
                        }

                        MirrorProtocol.TYPE_STREAM_META -> {
                            if (payloadLen >= 12) {
                                val bb = ByteBuffer.wrap(reusableReadBuffer, 0, 12)
                                val w = bb.int
                                val h = bb.int
                                val fps = bb.int
                                streamWidth = w
                                streamHeight = h
                                onStreamResolutionChanged(w, h, fps)
                            }
                        }

                        MirrorProtocol.TYPE_HEARTBEAT -> {
                            // Keep-alive recebido
                        }

                        MirrorProtocol.TYPE_TOUCHBACK_STATUS -> {
                            if (payloadLen >= 2) {
                                val touchOk = reusableReadBuffer[0].toInt() == 1
                                val muted = reusableReadBuffer[1].toInt() == 1
                                val dimPhone = payloadLen >= 3 && reusableReadBuffer[2].toInt() == 1
                                onRemoteStatusChanged(touchOk, muted, dimPhone)
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Oscilação ou queda de conexão com o celular: ${e.message}")
            } finally {
                val wasConnected = isConnected.getAndSet(false)
                closeCurrentSocket()
                if (isActive.get()) {
                    val msg = if (wasConnected) {
                        "Sinal oscilou. Reconectando automaticamente..."
                    } else {
                        "Aguardando celular ($host)... Tentando reconexão..."
                    }
                    onConnectionStateChanged(false, msg)
                    try {
                        Thread.sleep(1000L)
                    } catch (_: InterruptedException) {}
                }
            }
        }
    }

    private fun upstreamWriterLoop(socket: Socket, out: DataOutputStream) {
        try {
            while (isActive.get() && isConnected.get() && !socket.isClosed) {
                val cmd = upstreamQueue.poll(500L, TimeUnit.MILLISECONDS) ?: continue
                // Pacote fixo de 14 bytes: [CmdType:1B][Action:1B][Float1:4B][Float2:4B][IntExtra:4B]
                out.writeByte(cmd.cmdType.toInt())
                out.writeByte(cmd.action.toInt())
                out.writeFloat(cmd.float1)
                out.writeFloat(cmd.float2)
                out.writeInt(cmd.intExtra)
                if (upstreamQueue.isEmpty()) {
                    out.flush()
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Erro ao enviar comando Touchback: ${e.message}")
        }
    }

    /**
     * Envia evento de toque normalizado (0.0 a 1.0) da tela capacitiva da T3L para o celular.
     */
    fun sendTouchEvent(action: Byte, normX: Float, normY: Float, pointerId: Int = 0) {
        if (!isConnected.get()) return
        // Se a fila encher durante um movimento rápido, descarta o MOVE mais antigo
        if (upstreamQueue.remainingCapacity() == 0 && action == MirrorProtocol.TOUCH_ACTION_MOVE) {
            return
        }
        upstreamQueue.offer(
            UpstreamCommand(
                cmdType = MirrorProtocol.CMD_TOUCH_EVENT,
                action = action,
                float1 = normX.coerceIn(0f, 1f),
                float2 = normY.coerceIn(0f, 1f),
                intExtra = pointerId
            )
        )
    }

    fun requestKeyframe() {
        if (!isConnected.get()) return
        upstreamQueue.offer(
            UpstreamCommand(
                cmdType = MirrorProtocol.CMD_REQUEST_IDR,
                action = 0,
                float1 = 0f,
                float2 = 0f,
                intExtra = 0
            )
        )
    }

    fun requestProfileChange(profileIndex: Int) {
        if (!isConnected.get()) return
        upstreamQueue.offer(
            UpstreamCommand(
                cmdType = MirrorProtocol.CMD_CHANGE_PROFILE,
                action = 0,
                float1 = 0f,
                float2 = 0f,
                intExtra = profileIndex
            )
        )
    }

    fun sendSystemNavigation(navAction: Int) {
        if (!isConnected.get()) return
        upstreamQueue.offer(
            UpstreamCommand(
                cmdType = MirrorProtocol.CMD_SYSTEM_NAV,
                action = 0,
                float1 = 0f,
                float2 = 0f,
                intExtra = navAction
            )
        )
    }

    fun sendMediaControl(mediaAction: Int) {
        if (!isConnected.get()) return
        upstreamQueue.offer(
            UpstreamCommand(
                cmdType = MirrorProtocol.CMD_MEDIA_CONTROL,
                action = 0,
                float1 = 0f,
                float2 = 0f,
                intExtra = mediaAction
            )
        )
    }

    fun launchRemoteApp(appId: Int) {
        if (!isConnected.get()) return
        upstreamQueue.offer(
            UpstreamCommand(
                cmdType = MirrorProtocol.CMD_LAUNCH_APP,
                action = 0,
                float1 = 0f,
                float2 = 0f,
                intExtra = appId
            )
        )
    }

    fun stopRemoteSender() {
        if (!isConnected.get()) return
        upstreamQueue.offer(
            UpstreamCommand(
                cmdType = MirrorProtocol.CMD_STOP_REMOTE,
                action = 0,
                float1 = 0f,
                float2 = 0f,
                intExtra = 0
            )
        )
    }

    private fun closeCurrentSocket() {
        try {
            currentSocket?.close()
        } catch (_: Exception) {}
        currentSocket = null
    }

    fun disconnect() {
        isActive.set(false)
        isConnected.set(false)
        upstreamQueue.clear()
        closeCurrentSocket()
        audioPlayer.stop()
        onConnectionStateChanged(false, "Desconectado")
    }
}
