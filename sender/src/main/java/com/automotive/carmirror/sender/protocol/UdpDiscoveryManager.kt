package com.automotive.carmirror.sender.protocol

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.util.Log
import kotlinx.coroutines.*
import java.net.*

/**
 * Gerenciador de Descoberta Zero-Config Duplo (UDP Broadcast + NSD/mDNS).
 * - Engine 1: UDP Broadcast ativo na porta 49150 em todas as sub-redes (Hotspot ap0/swlan0 + Wi-Fi).
 * - Engine 2: Registro oficial NSD / mDNS (_carmirror._tcp.) para roteadores que filtram broadcast.
 * - Responde instantaneamente a probes da central multimídia T3L.
 */
class UdpDiscoveryManager(
    private val context: Context,
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
    private var nsdManager: NsdManager? = null
    private var nsdRegListener: NsdManager.RegistrationListener? = null

    fun start(scope: CoroutineScope) {
        if (discoveryJob?.isActive == true) return
        registerNsdService()

        discoveryJob = scope.launch(Dispatchers.IO) {
            try {
                val socket = DatagramSocket(null).apply {
                    reuseAddress = true
                    broadcast = true
                    bind(InetSocketAddress(MirrorProtocol.UDP_DISCOVERY_PORT))
                }
                udpSocket = socket

                // Coroutine 1: Responde imediatamente a probes UDP da T3L
                launch {
                    val recvBuf = ByteArray(512)
                    while (isActive && !socket.isClosed) {
                        try {
                            val packet = DatagramPacket(recvBuf, recvBuf.size)
                            socket.receive(packet)
                            val msg = String(packet.data, 0, packet.length, Charsets.UTF_8)
                            if (msg.startsWith(MirrorProtocol.DISCOVERY_MAGIC_PROBE)) {
                                val localIp = findBestLocalIpForPeer(packet.address)
                                    ?: getLocalIpAddresses().firstOrNull()
                                    ?: "0.0.0.0"
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

                // Coroutine 2: Emite Broadcast ativo em todas as interfaces Wi-Fi/Hotspot a cada 900ms
                while (isActive && !socket.isClosed) {
                    broadcastBeaconToAllInterfaces(socket)
                    delay(900L)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Falha ao iniciar UdpDiscoveryManager: ${e.message}", e)
            }
        }
    }

    private fun registerNsdService() {
        try {
            val mgr = context.applicationContext.getSystemService(Context.NSD_SERVICE) as NsdManager
            val serviceInfo = NsdServiceInfo().apply {
                serviceName = "${MirrorProtocol.NSD_SERVICE_NAME}_${Build.MODEL.replace(" ", "")}"
                serviceType = MirrorProtocol.NSD_SERVICE_TYPE
                port = tcpPort
            }
            val listener = object : NsdManager.RegistrationListener {
                override fun onServiceRegistered(info: NsdServiceInfo) {
                    Log.i(TAG, "Serviço mDNS/NSD registrado: ${info.serviceName}")
                }
                override fun onRegistrationFailed(info: NsdServiceInfo, errorCode: Int) {
                    Log.w(TAG, "Registro mDNS/NSD falhou (code=$errorCode)")
                }
                override fun onServiceUnregistered(info: NsdServiceInfo) {}
                override fun onUnregistrationFailed(info: NsdServiceInfo, errorCode: Int) {}
            }
            mgr.registerService(serviceInfo, NsdManager.PROTOCOL_DNS_SD, listener)
            nsdManager = mgr
            nsdRegListener = listener
        } catch (e: Exception) {
            Log.w(TAG, "Aviso ao registrar mDNS/NSD: ${e.message}")
        }
    }

    fun stop() {
        discoveryJob?.cancel()
        discoveryJob = null
        try {
            udpSocket?.close()
        } catch (_: Exception) {}
        udpSocket = null

        try {
            nsdRegListener?.let { nsdManager?.unregisterService(it) }
        } catch (_: Exception) {}
        nsdRegListener = null
        nsdManager = null
    }

    private fun buildBeaconPayload(ipAddress: String): ByteArray {
        val profile = getCurrentProfile()
        val deviceName = Build.MODEL.replace("|", "_")
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
                            socket.send(
                                DatagramPacket(payload, payload.size, bcast, MirrorProtocol.UDP_DISCOVERY_PORT)
                            )
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
