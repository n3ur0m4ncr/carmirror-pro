package com.automotive.carmirror.receiver.ota

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.core.content.FileProvider
import androidx.core.content.pm.PackageInfoCompat
import kotlinx.coroutines.*
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.URL

data class OtaUpdateInfo(
    val sourceLabel: String,
    val downloadUrl: String,
    val remoteVersionCode: Long,
    val remoteVersionName: String,
    val isNewer: Boolean
)

/**
 * Gerenciador Híbrido de Atualização OTA para a Central Multimídia Allwinner T3L:
 * - Consulta automaticamente o GitHub Releases na nuvem (funciona via Hotspot 4G/5G em qualquer lugar!).
 * - Também suporta atualização via Wi-Fi local do PC (UDP 49151 / HTTP 8765).
 */
class OtaUpdateManager(private val context: Context) {

    companion object {
        private const val TAG = "OtaUpdateManagerT3L"
        const val UDP_OTA_PORT = 49151
        const val DEFAULT_HTTP_PORT = 8765
        const val OTA_MAGIC = "CARMIRROR_OTA_V1"
        const val GITHUB_VERSION_URL =
            "https://github.com/n3ur0m4ncr/carmirror-pro/releases/latest/download/version.json"
    }

    private var listenerJob: Job? = null
    private var udpSocket: DatagramSocket? = null

    fun getCurrentVersionCode(): Long {
        return try {
            val info = context.packageManager.getPackageInfo(context.packageName, 0)
            PackageInfoCompat.getLongVersionCode(info)
        } catch (_: Exception) {
            1L
        }
    }

    fun getCurrentVersionName(): String {
        return try {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "1.0"
        } catch (_: Exception) {
            "1.0"
        }
    }

    /**
     * Verifica automaticamente novas versões no GitHub Releases (sem precisar de PC ligado ou pendrive!).
     */
    suspend fun checkGitHubRelease(): OtaUpdateInfo? = withContext(Dispatchers.IO) {
        try {
            val conn = openConnectionFollowingRedirects(GITHUB_VERSION_URL, 7000, 7000)
                ?: return@withContext null
            if (conn.responseCode != 200) {
                conn.disconnect()
                return@withContext null
            }
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            conn.disconnect()

            val json = JSONObject(body)
            val recvObj = json.getJSONObject("receiver")
            val remoteCode = recvObj.getLong("versionCode")
            val remoteName = recvObj.getString("versionName")
            val apkUrl = recvObj.getString("url")
            val localCode = getCurrentVersionCode()

            OtaUpdateInfo(
                sourceLabel = "GitHub Cloud",
                downloadUrl = apkUrl,
                remoteVersionCode = remoteCode,
                remoteVersionName = remoteName,
                isNewer = remoteCode > localCode
            )
        } catch (e: Exception) {
            Log.w(TAG, "Falha ao verificar GitHub Releases na T3L: ${e.message}")
            null
        }
    }

    fun startListeningLocalPc(
        scope: CoroutineScope,
        onUpdateOfferDetected: (OtaUpdateInfo) -> Unit
    ) {
        if (listenerJob?.isActive == true) return

        listenerJob = scope.launch(Dispatchers.IO) {
            try {
                val socket = DatagramSocket(null).apply {
                    reuseAddress = true
                    broadcast = true
                    bind(InetSocketAddress(UDP_OTA_PORT))
                }
                udpSocket = socket

                val buf = ByteArray(512)
                while (isActive && !socket.isClosed) {
                    try {
                        val packet = DatagramPacket(buf, buf.size)
                        socket.receive(packet)
                        val msg = String(packet.data, 0, packet.length, Charsets.UTF_8).trim()
                        if (msg.startsWith(OTA_MAGIC)) {
                            val parts = msg.split("|")
                            if (parts.size >= 7) {
                                val pcIp = packet.address?.hostAddress?.takeIf { it.isNotBlank() } ?: parts[1]
                                val port = parts[2].toIntOrNull() ?: DEFAULT_HTTP_PORT
                                val remoteCode = parts[5].toLongOrNull() ?: 0L
                                val remoteName = parts[6]
                                val localCode = getCurrentVersionCode()
                                if (remoteCode > 0L) {
                                    onUpdateOfferDetected(
                                        OtaUpdateInfo(
                                            sourceLabel = "PC Wi-Fi ($pcIp)",
                                            downloadUrl = "http://$pcIp:$port/receiver.apk",
                                            remoteVersionCode = remoteCode,
                                            remoteVersionName = remoteName,
                                            isNewer = remoteCode > localCode
                                        )
                                    )
                                }
                            }
                        }
                    } catch (_: Exception) {
                        if (socket.isClosed) break
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Erro no listener OTA UDP: ${e.message}")
            }
        }
    }

    fun stopListening() {
        listenerJob?.cancel()
        listenerJob = null
        try {
            udpSocket?.close()
        } catch (_: Exception) {}
        udpSocket = null
    }

    suspend fun checkLocalPcManually(pcIp: String, port: Int = DEFAULT_HTTP_PORT): OtaUpdateInfo? =
        withContext(Dispatchers.IO) {
            try {
                val conn = openConnectionFollowingRedirects("http://${pcIp.trim()}:$port/version.json", 4000, 4000)
                    ?: return@withContext null
                if (conn.responseCode != 200) {
                    conn.disconnect()
                    return@withContext null
                }
                val body = conn.inputStream.bufferedReader().use { it.readText() }
                conn.disconnect()

                val json = JSONObject(body)
                val recvObj = json.getJSONObject("receiver")
                val remoteCode = recvObj.getLong("versionCode")
                val remoteName = recvObj.getString("versionName")
                val localCode = getCurrentVersionCode()

                OtaUpdateInfo(
                    sourceLabel = "PC Wi-Fi (${pcIp.trim()})",
                    downloadUrl = "http://${pcIp.trim()}:$port/receiver.apk",
                    remoteVersionCode = remoteCode,
                    remoteVersionName = remoteName,
                    isNewer = remoteCode > localCode
                )
            } catch (e: Exception) {
                Log.w(TAG, "Falha ao consultar PC local em $pcIp:$port: ${e.message}")
                null
            }
        }

    suspend fun downloadAndInstall(
        info: OtaUpdateInfo,
        onProgress: (Int) -> Unit,
        onNeedInstallPermission: () -> Unit,
        onError: (String) -> Unit
    ) = withContext(Dispatchers.IO) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
                !context.packageManager.canRequestPackageInstalls()
            ) {
                withContext(Dispatchers.Main) {
                    onNeedInstallPermission()
                    val permIntent = Intent(
                        Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        Uri.parse("package:${context.packageName}")
                    ).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(permIntent)
                }
                return@withContext
            }

            val otaDir = File(context.cacheDir, "ota").apply { mkdirs() }
            val outFile = File(otaDir, "carmirror_receiver_t3l_update.apk")
            if (outFile.exists()) outFile.delete()

            val conn = openConnectionFollowingRedirects(info.downloadUrl, 8000, 30000)
            if (conn == null || conn.responseCode != 200) {
                val code = conn?.responseCode ?: -1
                conn?.disconnect()
                withContext(Dispatchers.Main) {
                    onError("Falha ao baixar APK (HTTP $code)")
                }
                return@withContext
            }

            val totalBytes = conn.contentLengthLong
            var downloaded = 0L
            conn.inputStream.use { input ->
                FileOutputStream(outFile).use { output ->
                    val buffer = ByteArray(16 * 1024)
                    var read: Int
                    var lastPercent = -1
                    while (input.read(buffer).also { read = it } != -1) {
                        output.write(buffer, 0, read)
                        downloaded += read
                        if (totalBytes > 0) {
                            val pct = ((downloaded * 100L) / totalBytes).toInt().coerceIn(0, 100)
                            if (pct != lastPercent) {
                                lastPercent = pct
                                withContext(Dispatchers.Main) { onProgress(pct) }
                            }
                        }
                    }
                }
            }
            conn.disconnect()

            if (outFile.length() < 50_000L) {
                withContext(Dispatchers.Main) {
                    onError("Arquivo APK inválido (${outFile.length()} bytes).")
                }
                return@withContext
            }

            val apkUri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.ota.fileprovider",
                outFile
            )

            withContext(Dispatchers.Main) {
                onProgress(100)
                val installIntent = Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(apkUri, "application/vnd.android.package-archive")
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(installIntent)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Erro no download OTA T3L: ${e.message}", e)
            withContext(Dispatchers.Main) {
                onError("Falha ao baixar atualização: ${e.message}")
            }
        }
    }

    private fun openConnectionFollowingRedirects(
        startUrl: String,
        connectTimeoutMs: Int,
        readTimeoutMs: Int
    ): HttpURLConnection? {
        var currentUrl = startUrl
        repeat(6) {
            val conn = (URL(currentUrl).openConnection() as HttpURLConnection).apply {
                connectTimeout = connectTimeoutMs
                readTimeout = readTimeoutMs
                requestMethod = "GET"
                instanceFollowRedirects = false
                setRequestProperty("User-Agent", "CarMirrorT3L-Android-OTA/1.6")
                setRequestProperty("Cache-Control", "no-cache")
            }
            val status = conn.responseCode
            if (status in listOf(
                    HttpURLConnection.HTTP_MOVED_PERM,
                    HttpURLConnection.HTTP_MOVED_TEMP,
                    HttpURLConnection.HTTP_SEE_OTHER,
                    307,
                    308
                )
            ) {
                val location = conn.getHeaderField("Location")
                conn.disconnect()
                if (location.isNullOrBlank()) return null
                currentUrl = URL(URL(currentUrl), location).toString()
            } else {
                return conn
            }
        }
        return null
    }
}
