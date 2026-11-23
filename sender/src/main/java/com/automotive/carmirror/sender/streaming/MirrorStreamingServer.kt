package com.automotive.carmirror.sender.streaming

import android.util.Log
import com.automotive.carmirror.sender.protocol.MirrorProtocol
import com.automotive.carmirror.sender.protocol.StreamProfile
import kotlinx.coroutines.*
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.ByteBuffer
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Servidor TCP Full-Duplex de Baixa Latência no Smartphone (App Transmissor).
 * - Envia pacotes multiplexados de Vídeo H.264 e Áudio PCM para a central T3L com TCP_NODELAY.
 * - Utiliza fila limitada (bounded queue) com descarte inteligente em caso de micro-oscilação Wi-Fi
 *   para evitar acúmulo de atraso (bufferbloat).
 * - Recebe no canal reverso (Upstream) os eventos de Toque (Touchback) e comandos da central T3L.
 */
class MirrorStreamingServer(
    private val port: Int = MirrorProtocol.TCP_STREAM_PORT,
    private val onClientConnectionChanged: (connected: Boolean, clientIp: String?) -> Unit,
    private val onClientReadyForStream: () -> Unit,
    private val onTouchCommandReceived: (action: Byte, normX: Float, normY: Float, pointerId: Int) -> Unit,
    private val onRequestIdrFrame: () -> Unit,
    private val onRequestProfileChange: (StreamProfile) -> Unit,
    private val onSystemNavCommand: (navAction: Int) -> Unit,
    private val onMediaControlCommand: (mediaAction: Int) -> Unit,
    private val onLaunchAppCommand: (appId: Int) -> Unit,
    private val onStopRemoteCommand: () -> Unit
) {
    companion object {
        private const val TAG = "MirrorStreamingServer"
        private const val MAX_OUTBOUND_QUEUE = 120
    }

    private class OutboundPacket(
        val type: Byte,
        val ptsUs: Long,
        val payload: ByteArray,
        val length: Int
    )

    private var serverSocket: ServerSocket? = null
    private var activeClientSocket: Socket? = null
    private val isServerRunning = AtomicBoolean(false)
    private val isClientConnected = AtomicBoolean(false)
    private val outboundQueue = LinkedBlockingQueue<OutboundPacket>(MAX_OUTBOUND_QUEUE)

    private var acceptThread: Thread? = null
    private var writerThread: Thread? = null
    private var readerThread: Thread? = null

    fun start() {
        if (isServerRunning.getAndSet(true)) return

        acceptThread = Thread({
            try {
                val srv = ServerSocket()
                srv.reuseAddress = true
                srv.bind(InetSocketAddress(port))
                serverSocket = srv
                Log.i(TAG, "Servidor TCP CarMirror aguardando conexão na porta $port")

                while (isServerRunning.get() && !srv.isClosed) {
                    val client = try {
                        srv.accept()
                    } catch (_: Exception) {
                        break
                    }
                    handleNewClient(client)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Erro no ServerSocket: ${e.message}", e)
            }
        }, "MirrorServerAcceptThread").apply { start() }
    }

    private fun handleNewClient(client: Socket) {
        // Fecha cliente anterior caso seja uma reconexão rápida da T3L
        disconnectActiveClient()

        try {
            client.tcpNoDelay = true
            client.trafficClass = 0x10 // IPTOS_LOWDELAY
            client.sendBufferSize = 256 * 1024
            client.receiveBufferSize = 32 * 1024
            client.keepAlive = true
        } catch (_: Exception) {}

        activeClientSocket = client
        outboundQueue.clear()
        isClientConnected.set(true)

        val remoteIp = client.inetAddress?.hostAddress ?: "T3L"
        Log.i(TAG, "Central Multimídia T3L conectada: $remoteIp")
        onClientConnectionChanged(true, remoteIp)

        // Thread de escrita (Downstream: Vídeo H.264 + Áudio PCM -> T3L)
        writerThread = Thread({
            clientWriterLoop(client)
        }, "MirrorServerWriterThread").apply {
            priority = Thread.MAX_PRIORITY
            start()
        }

        // Thread de leitura (Upstream: Touchback + Controle T3L -> Celular)
        readerThread = Thread({
            clientReaderLoop(client)
        }, "MirrorServerReaderThread").apply {
            priority = Thread.MAX_PRIORITY
            start()
        }

        // Notifica o encoder para enviar SPS/PPS + Metadados + IDR Keyframe imediato
        onClientReadyForStream()
    }

    /**
     * Enfileira um pacote de vídeo ou áudio para envio imediato à central T3L.
     */
    fun enqueuePacket(type: Byte, ptsUs: Long, data: ByteArray, length: Int) {
        if (!isClientConnected.get()) return

        // Se a fila encher devido a oscilação momentânea do Wi-Fi:
        // Prioriza vídeo e descarta áudio primeiro; se persistir, limpa e pede IDR
        if (outboundQueue.remainingCapacity() == 0) {
            if (type == MirrorProtocol.TYPE_AUDIO_PCM) {
                return
            }
            if (type != MirrorProtocol.TYPE_VIDEO_CONFIG) {
                outboundQueue.clear()
                onRequestIdrFrame()
                return
            }
        }

        val copy = ByteArray(length)
        System.arraycopy(data, 0, copy, 0, length)
        outboundQueue.offer(OutboundPacket(type, ptsUs, copy, length))
    }

    /**
     * Envia metadados da resolução ativa para a T3L ajustar o aspect ratio e decodificador.
     */
    fun sendStreamMetadata(profile: StreamProfile) {
        val metaBytes = ByteBuffer.allocate(12)
            .putInt(profile.width)
            .putInt(profile.height)
            .putInt(profile.fps)
            .array()
        enqueuePacket(MirrorProtocol.TYPE_STREAM_META, 0L, metaBytes, metaBytes.size)
    }

    fun sendTouchbackStatus(enabled: Boolean, audioMuted: Boolean, dimPhoneScreen: Boolean = false) {
        val statusBytes = byteArrayOf(
            if (enabled) 1 else 0,
            if (audioMuted) 1 else 0,
            if (dimPhoneScreen) 1 else 0
        )
        enqueuePacket(MirrorProtocol.TYPE_TOUCHBACK_STATUS, 0L, statusBytes, statusBytes.size)
    }

    private fun clientWriterLoop(socket: Socket) {
        try {
            val out = DataOutputStream(BufferedOutputStream(socket.getOutputStream(), 64 * 1024))
            var packetsSinceFlush = 0
            while (isServerRunning.get() && activeClientSocket === socket && !socket.isClosed) {
                val packet = outboundQueue.poll(1000L, TimeUnit.MILLISECONDS)
                if (packet != null) {
                    // Header de 13 bytes: [Type:1B][PtsUs:8B][Length:4B] + [Payload]
                    out.writeByte(packet.type.toInt())
                    out.writeLong(packet.ptsUs)
                    out.writeInt(packet.length)
                    out.write(packet.payload, 0, packet.length)
                    packetsSinceFlush++
                    // Flush imediato em quadros de vídeo, fila vazia ou a cada 4 pacotes
                    if (outboundQueue.isEmpty() ||
                        packet.type != MirrorProtocol.TYPE_AUDIO_PCM ||
                        packetsSinceFlush >= 4
                    ) {
                        out.flush()
                        packetsSinceFlush = 0
                    }
                } else {
                    // Heartbeat a cada 1s de ociosidade
                    out.writeByte(MirrorProtocol.TYPE_HEARTBEAT.toInt())
                    out.writeLong(System.nanoTime() / 1000L)
                    out.writeInt(0)
                    out.flush()
                    packetsSinceFlush = 0
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Conexão de escrita encerrada com a T3L: ${e.message}")
        } finally {
            disconnectActiveClient(socket)
        }
    }

    private fun clientReaderLoop(socket: Socket) {
        try {
            val input = DataInputStream(BufferedInputStream(socket.getInputStream(), 8 * 1024))
            while (isServerRunning.get() && activeClientSocket === socket && !socket.isClosed) {
                // Cada comando Upstream da T3L possui exatamente 14 bytes:
                // [CmdType:1B][Action:1B][Float1:4B][Float2:4B][IntExtra:4B]
                val cmdType = input.readByte()
                val subAction = input.readByte()
                val float1 = input.readFloat()
                val float2 = input.readFloat()
                val intExtra = input.readInt()

                when (cmdType) {
                    MirrorProtocol.CMD_TOUCH_EVENT -> {
                        onTouchCommandReceived(subAction, float1, float2, intExtra)
                    }
                    MirrorProtocol.CMD_REQUEST_IDR -> {
                        onRequestIdrFrame()
                    }
                    MirrorProtocol.CMD_CHANGE_PROFILE -> {
                        val profileIndex = intExtra.coerceIn(0, StreamProfile.ALL_PROFILES.lastIndex)
                        onRequestProfileChange(StreamProfile.ALL_PROFILES[profileIndex])
                    }
                    MirrorProtocol.CMD_SYSTEM_NAV -> {
                        onSystemNavCommand(intExtra)
                    }
                    MirrorProtocol.CMD_MEDIA_CONTROL -> {
                        onMediaControlCommand(intExtra)
                    }
                    MirrorProtocol.CMD_LAUNCH_APP -> {
                        onLaunchAppCommand(intExtra)
                    }
                    MirrorProtocol.CMD_STOP_REMOTE -> {
                        onStopRemoteCommand()
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Canal de leitura Upstream desconectado: ${e.message}")
        } finally {
            disconnectActiveClient(socket)
        }
    }

    @Synchronized
    private fun disconnectActiveClient(expectedSocket: Socket? = null) {
        // Evita race condition: uma thread antiga de um socket encerrado JAMAIS derruba uma nova conexão ativa!
        if (expectedSocket != null && activeClientSocket !== expectedSocket) {
            try {
                expectedSocket.close()
            } catch (_: Exception) {}
            return
        }
        val wasConnected = isClientConnected.getAndSet(false)
        outboundQueue.clear()
        try {
            activeClientSocket?.close()
        } catch (_: Exception) {}
        activeClientSocket = null
        if (wasConnected) {
            onClientConnectionChanged(false, null)
        }
    }

    fun stop() {
        isServerRunning.set(false)
        disconnectActiveClient()
        try {
            serverSocket?.close()
        } catch (_: Exception) {}
        serverSocket = null
    }
}
