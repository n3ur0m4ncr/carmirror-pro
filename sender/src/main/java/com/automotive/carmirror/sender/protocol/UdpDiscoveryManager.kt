package com.automotive.carmirror.sender.protocol

import android.os.Build
import android.util.Log
import kotlinx.coroutines.*
import java.net.*

/**
 * Gerenciador de Descoberta Zero-Config via UDP Broadcast.
 * Varre todas as interfaces de rede ativas (incluindo interface de Hotspot tethering como ap0/wlan1/swlan0)
 * e anuncia o servidor TCP para a central multimídia T3L a cada 1 segundo, além de responder
 * instantaneamente a pacotes de Probe vindos da T3L.
 */
class UdpDiscoveryManager(
    private val tcpPort: Int = MirrorProtocol.TCP_STREAM_PORT,
    private val getCurrentProfile: () -> StreamProfile
) {
    companion object {
        private const val TAG = "UdpDiscoveryManager"

        fun getLocalIpAddresses(): List<String> {
            val result = mutableListOf<String>()
            try {
                val interfaces = NetworkInterface.getNetworkInterfaces() ?: return result
                for (nif in interfaces) {
                    if (!nif.isUp || nif.isLoopback) continue
                    for (addr in nif.inetAddresses) {
                        if (addr is Inet4Address && !addr.isLoopbackAddress) {
                            addr.hostAddress?.let { result.add(it) }
                        }
                    }
                }
            } catch (_: Exception) {}
            return result
        }
    }

    private var discoveryJob: Job? = null
    private var udpSocket: DatagramSocket? = null

    fun start(scope: CoroutineScope) {
        if (discoveryJob?.isActive == true) return

        discoveryJob = scope.launch(Dispatchers.IO) {
            try {
                val socket = DatagramSocket(null).apply {
                    reuseAddress = true
                    broadcast = true
                    bind(InetSocketAddress(MirrorProtocol.UDP_DISCOVERY_PORT))
                }
                udpSocket = socket

                // Coroutine 1: Responder imediatamente a Probes da Central Multimídia T3L
                launch {
                    val recvBuf = ByteArray(512)
                    while (isActive && !socket.isClosed) {
                        try {
                            val packet = DatagramPacket(recvBuf, recvBuf.size)
                            socket.receive(packet)
                            val msg = String(packet.data, 0, packet.length, Charsets.UTF_8)
                            if (msg.startsWith(MirrorProtocol.DISCOVERY_MAGIC_PROBE)) {
                                val localIp = findBestLocalIpForPeer(packet.address) ?: getLocalIpAddresses().firstOrNull() ?: "0.0.0.0"
                                val beacon = buildBeaconPayload(localIp)
                                val replyPacket = DatagramPacket(
                                    beacon,
                                    beacon.size,
                                    packet.address,
                                    MirrorProtocol.UDP_DISCOVERY_PORT
                                )
                                socket.send(replyPacket)
                            }
                        } catch (_: SocketException) {
                            break
                        } catch (e: Exception) {
                            Log.w(TAG, "Erro ao processar probe UDP: ${e.message}")
                        }
                    }
                }

                // Coroutine 2: Emitir Broadcast ativo em todas as sub-redes Wi-Fi / Hotspot a cada 1s
                while (isActive && !socket.isClosed) {
                    broadcastBeaconToAllInterfaces(socket)
                    delay(1000L)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Falha ao iniciar UdpDiscoveryManager: ${e.message}", e)
            }
        }
    }

    fun stop() {
        discoveryJob?.cancel()
        discoveryJob = null
        try {
            udpSocket?.close()
        } catch (_: Exception) {}
        udpSocket = null
    }

    private fun buildBeaconPayload(ipAddress: String): ByteArray {
        val profile = getCurrentProfile()
        val deviceName = Build.MODEL.replace("|", "_")
        // Formato: CARMIRROR_SENDER_V1|<ip>|<tcpPort>|<width>|<height>|<fps>|<deviceName>
        val payload = "${MirrorProtocol.DISCOVERY_MAGIC_BEACON}|$ipAddress|$tcpPort|${profile.width}|${profile.height}|${profile.fps}|$deviceName"
        return payload.toByteArray(Charsets.UTF_8)
    }

    private fun broadcastBeaconToAllInterfaces(socket: DatagramSocket) {
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces() ?: return
            for (nif in interfaces) {
                if (!nif.isUp || nif.isLoopback) continue
                for (ifaceAddr in nif.interfaceAddresses) {
                    val inetAddr = ifaceAddr.address
                    if (inetAddr is Inet4Address) {
                        val bcast = ifaceAddr.broadcast ?: InetAddress.getByName("255.255.255.255")
                        val localIp = inetAddr.hostAddress ?: continue
                        val payload = buildBeaconPayload(localIp)
                        try {
                            val packet = DatagramPacket(
                                payload,
                                payload.size,
                                bcast,
                                MirrorProtocol.UDP_DISCOVERY_PORT
                            )
                            socket.send(packet)
                        } catch (_: Exception) {}
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Erro ao anunciar broadcast: ${e.message}")
        }
    }

    private fun findBestLocalIpForPeer(peerAddress: InetAddress): String? {
        val peerBytes = peerAddress.address
        if (peerBytes.size != 4) return null
        val interfaces = NetworkInterface.getNetworkInterfaces() ?: return null
        for (nif in interfaces) {
            if (!nif.isUp || nif.isLoopback) continue
            for (ifaceAddr in nif.interfaceAddresses) {
                val addr = ifaceAddr.address
                if (addr is Inet4Address) {
                    val localBytes = addr.address
                    // Compara os 3 primeiros octetos (/24 comum de Hotspot Android)
                    if (localBytes[0] == peerBytes[0] &&
                        localBytes[1] == peerBytes[1] &&
                        localBytes[2] == peerBytes[2]
                    ) {
                        return addr.hostAddress
                    }
                }
            }
        }
        return null
    }
}
