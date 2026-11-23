package com.automotive.carmirror.receiver.protocol

import android.content.Context
import android.net.wifi.WifiManager
import android.util.Log
import kotlinx.coroutines.*
import java.net.*
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Cliente de Descoberta Zero-Config otimizado para a placa Allwinner T3L.
 * 1. Adquire MulticastLock (necessário para drivers Wi-Fi Realtek/Allwinner da T3L não filtrarem UDP broadcast).
 * 2. Escuta beacons UDP na porta 49150.
 * 3. Envia probes ativos para o endereço de broadcast da sub-rede E diretamente para o IP do Gateway DHCP
 *    (pois quando a T3L conecta no Hotspot do celular, o celular é sempre o Gateway DHCP).
 */
class UdpDiscoveryClient(private val context: Context) {

    companion object {
        private const val TAG = "UdpDiscoveryClient"
    }

    private var discoveryJob: Job? = null
    private var udpSocket: DatagramSocket? = null
    private var multicastLock: WifiManager.MulticastLock? = null

    fun startDiscovery(
        scope: CoroutineScope,
        onSenderFound: (DiscoveredSender) -> Unit
    ) {
        if (discoveryJob?.isActive == true) return

        acquireMulticastLock()

        discoveryJob = scope.launch(Dispatchers.IO) {
            try {
                val socket = DatagramSocket(null).apply {
                    reuseAddress = true
                    broadcast = true
                    bind(InetSocketAddress(MirrorProtocol.UDP_DISCOVERY_PORT))
                }
                udpSocket = socket

                // Coroutine 1: Envia probes UDP ativos a cada 800ms para sub-rede e Gateway do Hotspot
                launch {
                    val probeBytes = MirrorProtocol.DISCOVERY_MAGIC_PROBE.toByteArray(Charsets.UTF_8)
                    while (isActive && !socket.isClosed) {
                        sendActiveProbes(socket, probeBytes)
                        delay(800L)
                    }
                }

                // Coroutine 2: Recebe Beacons de resposta do celular
                val buffer = ByteArray(512)
                while (isActive && !socket.isClosed) {
                    try {
                        val packet = DatagramPacket(buffer, buffer.size)
                        socket.receive(packet)
                        val msg = String(packet.data, 0, packet.length, Charsets.UTF_8)
                        if (msg.startsWith(MirrorProtocol.DISCOVERY_MAGIC_BEACON)) {
                            val parts = msg.split("|")
                            if (parts.size >= 7) {
                                val senderIp = packet.address?.hostAddress?.takeIf { it.isNotBlank() } ?: parts[1]
                                val port = parts[2].toIntOrNull() ?: MirrorProtocol.TCP_STREAM_PORT
                                val w = parts[3].toIntOrNull() ?: 1024
                                val h = parts[4].toIntOrNull() ?: 600
                                val fps = parts[5].toIntOrNull() ?: 30
                                val name = parts[6]

                                onSenderFound(
                                    DiscoveredSender(
                                        ipAddress = senderIp,
                                        tcpPort = port,
                                        width = w,
                                        height = h,
                                        fps = fps,
                                        deviceName = name
                                    )
                                )
                            }
                        }
                    } catch (_: SocketException) {
                        break
                    } catch (e: Exception) {
                        Log.w(TAG, "Erro ao ler pacote de descoberta: ${e.message}")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Falha ao abrir socket UDP de descoberta: ${e.message}")
            }
        }
    }

    private fun sendActiveProbes(socket: DatagramSocket, probeBytes: ByteArray) {
        // 1. Envia diretamente para o Gateway Wi-Fi (IP do celular quando em modo Roteador Hotspot)
        getWifiGatewayIp()?.let { gatewayIp ->
            try {
                val addr = InetAddress.getByName(gatewayIp)
                socket.send(DatagramPacket(probeBytes, probeBytes.size, addr, MirrorProtocol.UDP_DISCOVERY_PORT))
            } catch (_: Exception) {}
        }

        // 2. Envia para os endereços de broadcast das interfaces de rede ativas
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces() ?: return
            for (nif in interfaces) {
                if (!nif.isUp || nif.isLoopback) continue
                for (ifaceAddr in nif.interfaceAddresses) {
                    val bcast = ifaceAddr.broadcast ?: continue
                    try {
                        socket.send(DatagramPacket(probeBytes, probeBytes.size, bcast, MirrorProtocol.UDP_DISCOVERY_PORT))
                    } catch (_: Exception) {}
                }
            }
        } catch (_: Exception) {}
    }

    @Suppress("DEPRECATION")
    fun getWifiGatewayIp(): String? {
        return try {
            val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            val dhcp = wifiManager.dhcpInfo ?: return null
            if (dhcp.gateway == 0) return null
            val bytes = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(dhcp.gateway).array()
            InetAddress.getByAddress(bytes).hostAddress
        } catch (_: Exception) {
            null
        }
    }

    private fun acquireMulticastLock() {
        try {
            val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            multicastLock = wifiManager.createMulticastLock("CarMirrorT3LDiscoveryLock").apply {
                setReferenceCounted(false)
                acquire()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Não foi possível adquirir MulticastLock: ${e.message}")
        }
    }

    fun stopDiscovery() {
        discoveryJob?.cancel()
        discoveryJob = null
        try {
            udpSocket?.close()
        } catch (_: Exception) {}
        udpSocket = null
        try {
            if (multicastLock?.isHeld == true) {
                multicastLock?.release()
            }
        } catch (_: Exception) {}
        multicastLock = null
    }
}
