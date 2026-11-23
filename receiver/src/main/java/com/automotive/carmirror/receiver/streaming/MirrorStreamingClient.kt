package com.automotive.carmirror.receiver.streaming

import android.media.MediaCodec
import android.net.Network
import android.util.Log
import com.automotive.carmirror.receiver.protocol.MirrorProtocol
import okio.buffer
import okio.sink
import okio.source
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Cliente TCP Full-Duplex Profissional com Square Okio (SegmentPool Zero-Copy) para a Central Allwinner T3L.
 * - Vincula o socket explicitamente à rede Wi-Fi (Network.bindSocket) -> nunca desconecta em túneis/garagens sem internet!
 * - Buffer único reutilizável (Zero-GC Heap Allocation por quadro) para os 2 GB RAM da T3L.
 * - Reconexão ultrarrápida (300ms) + Resposta instantânea de Ping/Pong para Bitrate Adaptativo (ABR).
 */
class MirrorStreamingClient(
    private val videoDecoder: H264HardwareDecoder,
    private val audioPlayer: LowLatencyAudioPlayer,
    private val getActiveWifiNetwork: () -> Network?,
    private val onConnectionStateChanged: (connected: Boolean, statusMessage: String) -> Unit,
    private val onStreamResolutionChanged: (width: Int, height: Int, fps: Int) -> Unit,
    private val onRemoteStatusChanged: (touchbackEnabled: Boolean, audioMuted: Boolean, dimPhoneScreen: Boolean) -> Unit,
    private val onTelemetryReceived: (rttMs: Int, bitrateKbps: Int, fps: Int) -> Unit
) {
    companion object {
        private const val TAG = "MirrorStreamingClient"
        private const val CONNECT_TIMEOUT_MS = 2500
        private const val READ_TIMEOUT_MS = 8000
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

        if (isActive.get()) {
            if (targetHost == cleanHost && targetPort == port) {
                return
            }
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
        }, "T3LOkioStreamSupervisor").apply {
            priority = Thread.MAX_PRIORITY
            start()
        }
    }

    private fun connectionSupervisorLoop() {
        var reusableReadBuffer = ByteArray(512 * 1024)

        while (isActive.get()) {
            val host = targetHost
            val port = targetPort
            if (host.isBlank()) {
                try { Thread.sleep(300L) } catch (_: InterruptedException) {}
                continue
            }

            try {
                onConnectionStateChanged(false, "Conectando em alta velocidade ($host:$port)...")
                val socket = Socket()
                socket.tcpNoDelay = true
                socket.trafficClass = 0x10 // IPTOS_LOWDELAY
                socket.receiveBufferSize = 256 * 1024
                socket.sendBufferSize = 32 * 1024
                socket.soTimeout = READ_TIMEOUT_MS
                socket.keepAlive = true

                // Vincula explicitamente ao Wi-Fi local (impede queda se o 4G/5G cair em túneis!)
                try {
                    getActiveWifiNetwork()?.bindSocket(socket)
                } catch (_: Exception) {}

                socket.connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS)

                currentSocket = socket
                upstreamQueue.clear()
                videoDecoder.resetKeyframeGate()
                isConnected.set(true)
                audioPlayer.start()
                onConnectionStateChanged(true, "Conectado ($host)")

                // Thread Upstream Okio (Touchback + Controle + Pong RTT)
                val sink = socket.sink().buffer()
                writerThread = Thread({
                    upstreamWriterLoop(socket, sink)
                }, "T3LOkioUpstreamWriter").apply {
                    priority = Thread.MAX_PRIORITY
                    start()
                }

                // Solicita SPS/PPS + IDR Keyframe imediato
                requestKeyframe()

                // Loop de leitura Downstream Okio (SegmentPool Zero-Copy)
                val source = socket.source().buffer()
                while (isActive.get() && isConnected.get() && !socket.isClosed) {
                    val packetType = source.readByte()
                    val ptsUs = source.readLong()
                    val payloadLen = source.readInt()

                    if (payloadLen < 0 || payloadLen > 4 * 1024 * 1024) {
                        throw IllegalStateException("Tamanho de pacote inválido: $payloadLen")
                    }

                    if (payloadLen > reusableReadBuffer.size) {
                        reusableReadBuffer = ByteArray(payloadLen + 64 * 1024)
                    }

                    if (payloadLen > 0) {
                        var offset = 0
                        while (offset < payloadLen) {
                            val readNow = source.read(reusableReadBuffer, offset, payloadLen - offset)
                            if (readNow == -1) {
                                throw java.io.EOFException("Conexão encerrada durante leitura do quadro")
                            }
                            offset += readNow
                        }
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

                        MirrorProtocol.TYPE_TOUCHBACK_STATUS -> {
                            if (payloadLen >= 2) {
                                val touchOk = reusableReadBuffer[0].toInt() == 1
                                val muted = reusableReadBuffer[1].toInt() == 1
                                val dimPhone = payloadLen >= 3 && reusableReadBuffer[2].toInt() == 1
                                onRemoteStatusChanged(touchOk, muted, dimPhone)
                            }
                        }

                        MirrorProtocol.TYPE_PING_TELEMETRY -> {
                            if (payloadLen >= 16) {
                                val bb = ByteBuffer.wrap(reusableReadBuffer, 0, 16)
                                val pingSeq = bb.int
                                val rttMs = bb.int
                                val bitrateKbps = bb.int
                                val fps = bb.int
                                // Responde PONG imediatamente para o motor ABR do celular
                                sendPong(pingSeq)
                                onTelemetryReceived(rttMs, bitrateKbps, fps)
                            }
                        }

                        MirrorProtocol.TYPE_HEARTBEAT -> {
                            // Keep-alive recebido
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Reconexão rápida acionada: ${e.message}")
            } finally {
                val wasConnected = isConnected.getAndSet(false)
                closeCurrentSocket()
                if (isActive.get()) {
                    val msg = if (wasConnected) {
                        "Reconectando em 0.3s..."
                    } else {
                        "Aguardando CarMirror Pro ($host)..."
                    }
                    onConnectionStateChanged(false, msg)
                    try {
                        // Reconexão ultrarrápida (300ms)
                        Thread.sleep(300L)
                    } catch (_: InterruptedException) {}
                }
            }
        }
    }

    private fun upstreamWriterLoop(socket: Socket, sink: okio.BufferedSink) {
        try {
            while (isActive.get() && isConnected.get() && !socket.isClosed) {
                val cmd = upstreamQueue.poll(500L, TimeUnit.MILLISECONDS) ?: continue
                sink.writeByte(cmd.cmdType.toInt())
                sink.writeByte(cmd.action.toInt())
                sink.writeInt(cmd.float1.toBits())
                sink.writeInt(cmd.float2.toBits())
                sink.writeInt(cmd.intExtra)
                if (upstreamQueue.isEmpty()) {
                    sink.flush()
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Okio Upstream Writer encerrado: ${e.message}")
        }
    }

    private fun sendPong(pingSeq: Int) {
        if (!isConnected.get()) return
        upstreamQueue.offer(
            UpstreamCommand(
                cmdType = MirrorProtocol.CMD_PONG,
                action = 0,
                float1 = 0f,
                float2 = 0f,
                intExtra = pingSeq
            )
        )
    }

    fun sendTouchEvent(action: Byte, normX: Float, normY: Float, pointerId: Int = 0) {
        if (!isConnected.get()) return
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
