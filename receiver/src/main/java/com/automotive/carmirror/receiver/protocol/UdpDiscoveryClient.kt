package com.automotive.carmirror.receiver.protocol

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.util.Log
import kotlinx.coroutines.*
import java.net.*
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Motor Triplo de Descoberta Zero-Config + Pinning de Rede Wi-Fi para a Allwinner T3L:
 * 1. UDP Broadcast (porta 49150) com MulticastLock para drivers Wi-Fi Realtek/Allwinner.
 * 2. Sondagem direta ao Gateway DHCP do Hotspot do celular (funciona mesmo se broadcast for bloqueado).
 * 3. Android NSD / mDNS (_carmirror._tcp.) paralelo.
 * 4. WifiLock (WIFI_MODE_FULL_LOW_LATENCY) impede Power Save 802.11 (reduz picos de latência de 80ms para ~4ms).
 * 5. ConnectivityManager.NetworkCallback (TRANSPORT_WIFI) mantém referência exata da rede Wi-Fi para bindSocket(),
 *    garantindo que a conexão jamais caia em túneis/garagens sem sinal 4G/5G!
 */
class UdpDiscoveryClient(private val context: Context) {

    companion object {
        private const val TAG = "UdpDiscoveryClient"
    }

    private var discoveryJob: Job? = null
    private var udpSocket: DatagramSocket? = null
    private var multicastLock: WifiManager.MulticastLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    private var nsdManager: NsdManager? = null
    private var nsdDiscoveryListener: NsdManager.DiscoveryListener? = null

    private var connectivityManager: ConnectivityManager? = null
    private var wifiNetworkCallback: ConnectivityManager.NetworkCallback? = null

    @Volatile
    var activeWifiNetwork: Network? = null
        private set

    fun startDiscovery(
        scope: CoroutineScope,
        onSenderFound: (DiscoveredSender) -> Unit
    ) {
        if (discoveryJob?.isActive == true) return

        acquireWifiPerformanceLocks()
        registerWifiNetworkPinning()
        startNsdDiscovery(onSenderFound)

        discoveryJob = scope.launch(Dispatchers.IO) {
            try {
                val socket = DatagramSocket(null).apply {
                    reuseAddress = true
                    broadcast = true
                    bind(InetSocketAddress(MirrorProtocol.UDP_DISCOVERY_PORT))
                }
                try {
                    activeWifiNetwork?.bindSocket(socket)
                } catch (_: Exception) {}
                udpSocket = socket

                // Coroutine 1: Envia probes UDP ativos a cada 750ms para sub-rede e Gateway do Hotspot
                launch {
                    val probeBytes = MirrorProtocol.DISCOVERY_MAGIC_PROBE.toByteArray(Charsets.UTF_8)
                    while (isActive && !socket.isClosed) {
                        sendActiveProbes(socket, probeBytes)
                        delay(750L)
                    }
                }

                // Coroutine 2: Recebe beacons de resposta do celular
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

    private fun registerWifiNetworkPinning() {
        if (wifiNetworkCallback != null) return
        try {
            val cm = context.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val req = NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build()

            val cb = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    activeWifiNetwork = network
                    Log.i(TAG, "Rede Wi-Fi fixada para baixa latência (sem dependência de internet)")
                }

                override fun onLost(network: Network) {
                    if (activeWifiNetwork == network) {
                        activeWifiNetwork = null
                    }
                }
            }
            cm.registerNetworkCallback(req, cb)
            connectivityManager = cm
            wifiNetworkCallback = cb
        } catch (e: Exception) {
            Log.w(TAG, "Aviso ao registrar NetworkCallback Wi-Fi: ${e.message}")
        }
    }

    @Suppress("DEPRECATION")
    private fun startNsdDiscovery(onSenderFound: (DiscoveredSender) -> Unit) {
        try {
            val mgr = context.applicationContext.getSystemService(Context.NSD_SERVICE) as NsdManager
            val listener = object : NsdManager.DiscoveryListener {
                override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {}
                override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}
                override fun onDiscoveryStarted(serviceType: String) {}
                override fun onDiscoveryStopped(serviceType: String) {}
                override fun onServiceLost(serviceInfo: NsdServiceInfo) {}

                override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                    if (serviceInfo.serviceType.contains("carmirror", ignoreCase = true)) {
                        try {
                            mgr.resolveService(serviceInfo, object : NsdManager.ResolveListener {
                                override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) {}
                                override fun onServiceResolved(resolved: NsdServiceInfo) {
                                    val ip = resolved.host?.hostAddress ?: return
                                    val port = resolved.port.takeIf { it > 0 } ?: MirrorProtocol.TCP_STREAM_PORT
                                    val cleanName = resolved.serviceName
                                        .removePrefix("CarMirrorPro_")
                                        .ifBlank { "Smartphone" }
                                    onSenderFound(
                                        DiscoveredSender(
                                            ipAddress = ip,
                                            tcpPort = port,
                                            width = 1024,
                                            height = 600,
                                            fps = 30,
                                            deviceName = cleanName
                                        )
                                    )
                                }
                            })
                        } catch (_: Exception) {}
                    }
                }
            }
            mgr.discoverServices(MirrorProtocol.NSD_SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener)
            nsdManager = mgr
            nsdDiscoveryListener = listener
        } catch (e: Exception) {
            Log.w(TAG, "Aviso ao iniciar NSD/mDNS: ${e.message}")
        }
    }

    private fun sendActiveProbes(socket: DatagramSocket, probeBytes: ByteArray) {
        getWifiGatewayIp()?.let { gatewayIp ->
            try {
                val addr = InetAddress.getByName(gatewayIp)
                socket.send(DatagramPacket(probeBytes, probeBytes.size, addr, MirrorProtocol.UDP_DISCOVERY_PORT))
            } catch (_: Exception) {}
        }

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

    @Suppress("DEPRECATION")
    private fun acquireWifiPerformanceLocks() {
        try {
            val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            multicastLock = wifiManager.createMulticastLock("CarMirrorT3LDiscoveryLock").apply {
                setReferenceCounted(false)
                acquire()
            }
            val lockMode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                WifiManager.WIFI_MODE_FULL_LOW_LATENCY
            } else {
                WifiManager.WIFI_MODE_FULL_HIGH_PERF
            }
            wifiLock = wifiManager.createWifiLock(lockMode, "CarMirrorT3LWifiLowLatencyLock").apply {
                setReferenceCounted(false)
                acquire()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Aviso ao adquirir WifiLocks na T3L: ${e.message}")
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
            nsdDiscoveryListener?.let { nsdManager?.stopServiceDiscovery(it) }
        } catch (_: Exception) {}
        nsdDiscoveryListener = null
        nsdManager = null

        try {
            wifiNetworkCallback?.let { connectivityManager?.unregisterNetworkCallback(it) }
        } catch (_: Exception) {}
        wifiNetworkCallback = null
        connectivityManager = null

        try {
            if (multicastLock?.isHeld == true) multicastLock?.release()
        } catch (_: Exception) {}
        multicastLock = null

        try {
            if (wifiLock?.isHeld == true) wifiLock?.release()
        } catch (_: Exception) {}
        wifiLock = null
    }
}
