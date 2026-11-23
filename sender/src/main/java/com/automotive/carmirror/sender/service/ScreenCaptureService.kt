package com.automotive.carmirror.sender.service

import android.Manifest
import android.app.*
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.AudioManager
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.view.KeyEvent
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.automotive.carmirror.sender.protocol.MirrorProtocol
import com.automotive.carmirror.sender.protocol.StreamProfile
import com.automotive.carmirror.sender.protocol.UdpDiscoveryManager
import com.automotive.carmirror.sender.streaming.H264VideoEncoder
import com.automotive.carmirror.sender.streaming.InternalAudioCapturer
import com.automotive.carmirror.sender.streaming.MirrorStreamingServer
import com.automotive.carmirror.sender.ui.SenderMainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Foreground Service de espelhamento em tempo real (MediaProjection + H.264 + Áudio PCM + UDP Discovery).
 * Cumpre integralmente as exigências do Android 14/15 (API 34+) com foregroundServiceType="mediaProjection".
 */
class ScreenCaptureService : Service() {

    companion object {
        private const val TAG = "ScreenCaptureService"
        private const val CHANNEL_ID = "CarMirrorProjectionChannel"
        private const val NOTIFICATION_ID = 1001

        const val ACTION_START_CAPTURE = "com.automotive.carmirror.sender.ACTION_START_CAPTURE"
        const val ACTION_STOP_CAPTURE = "com.automotive.carmirror.sender.ACTION_STOP_CAPTURE"
        const val ACTION_CHANGE_PROFILE = "com.automotive.carmirror.sender.ACTION_CHANGE_PROFILE"
        const val ACTION_TOGGLE_DIM_SCREEN = "com.automotive.carmirror.sender.ACTION_TOGGLE_DIM_SCREEN"
        const val ACTION_TOGGLE_RECEIVER_ONLY_AUDIO = "com.automotive.carmirror.sender.ACTION_TOGGLE_RECEIVER_ONLY_AUDIO"

        const val EXTRA_RESULT_CODE = "extra_result_code"
        const val EXTRA_RESULT_INTENT = "extra_result_intent"
        const val EXTRA_PROFILE_INDEX = "extra_profile_index"

        private val _serviceState = MutableStateFlow(SenderServiceState())
        val serviceState: StateFlow<SenderServiceState> = _serviceState
    }

    data class SenderServiceState(
        val isStreaming: Boolean = false,
        val isReceiverConnected: Boolean = false,
        val connectedReceiverIp: String? = null,
        val activeProfile: StreamProfile = StreamProfile.PROFILE_T3L_NATIVE,
        val localIps: List<String> = emptyList(),
        val isDimScreenActive: Boolean = false,
        val isReceiverOnlyAudio: Boolean = true,
        val rttMs: Int = 0,
        val activeBitrateKbps: Int = 0,
        val liveFps: Int = 0
    )

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var mediaProjection: MediaProjection? = null
    private var videoEncoder: H264VideoEncoder? = null
    private var audioCapturer: InternalAudioCapturer? = null
    private var streamingServer: MirrorStreamingServer? = null
    private var discoveryManager: UdpDiscoveryManager? = null
    private var isScreenReceiverRegistered = false

    @Volatile
    private var isRemoteAudioMuted = false

    @Volatile
    private var isDimScreenActive = false

    @Volatile
    private var isReceiverOnlyAudio = true

    @Volatile
    private var savedPhoneMusicVolume: Int = -1

    @Volatile
    private var savedSystemBrightness: Int = -1

    @Volatile
    private var savedSystemBrightnessMode: Int = -1

    /**
     * Monitora se a tela física do celular foi apagada pelo botão Power durante o espelhamento.
     * Reacende o display automaticamente em modo Economia de Tela (0.5% brilho AMOLED) para impedir
     * que o SurfaceFlinger congele ou apague a tela da central multimídia T3L!
     */
    private val screenOffReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == Intent.ACTION_SCREEN_OFF && _serviceState.value.isStreaming) {
                Log.i(TAG, "Tela do celular apagada durante espelhamento — ativando Modo Tela Mínima (0.5%) e mantendo stream ativo")
                isDimScreenActive = true
                _serviceState.value = _serviceState.value.copy(isDimScreenActive = true)
                TouchAccessibilityService.instance?.updateKeepAwakeOverlay(true, dimPhoneScreen = true)
                wakeUpDisplayForContinuousMirroring()
                streamingServer?.sendTouchbackStatus(
                    TouchAccessibilityService.isServiceEnabled(),
                    isRemoteAudioMuted,
                    isDimScreenActive
                )
                videoEncoder?.requestInstantIdrFrame()
            }
        }
    }

    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            Log.i(TAG, "MediaProjection encerrado pelo sistema")
            stopMirroringAndSelf()
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START_CAPTURE -> {
                val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
                val resultData: Intent? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(EXTRA_RESULT_INTENT, Intent::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(EXTRA_RESULT_INTENT)
                }
                val profileIndex = intent.getIntExtra(EXTRA_PROFILE_INDEX, 0)
                    .coerceIn(0, StreamProfile.ALL_PROFILES.lastIndex)
                val selectedProfile = StreamProfile.ALL_PROFILES[profileIndex]

                if (resultCode == Activity.RESULT_OK && resultData != null) {
                    startForegroundWithProjectionType("Transmitindo para Central Multimídia T3L...")
                    startMirroringSession(resultCode, resultData, selectedProfile)
                } else {
                    stopSelf()
                }
            }

            ACTION_CHANGE_PROFILE -> {
                val profileIndex = intent.getIntExtra(EXTRA_PROFILE_INDEX, 0)
                    .coerceIn(0, StreamProfile.ALL_PROFILES.lastIndex)
                val newProfile = StreamProfile.ALL_PROFILES[profileIndex]
                applyNewStreamProfile(newProfile)
            }

            ACTION_TOGGLE_DIM_SCREEN -> {
                toggleDimPhoneScreen()
            }

            ACTION_TOGGLE_RECEIVER_ONLY_AUDIO -> {
                toggleReceiverOnlyAudioMode()
            }

            ACTION_STOP_CAPTURE -> {
                stopMirroringAndSelf()
            }
        }
        return START_NOT_STICKY
    }

    private fun startForegroundWithProjectionType(statusText: String) {
        val notification = buildNotification(statusText)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val hasMicPermission = ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.RECORD_AUDIO
            ) == PackageManager.PERMISSION_GRANTED
            val fgsType = if (hasMicPermission) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION or
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            } else {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            }
            startForeground(NOTIFICATION_ID, notification, fgsType)
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun startMirroringSession(
        resultCode: Int,
        resultData: Intent,
        initialProfile: StreamProfile
    ) {
        stopActiveComponents()
        acquireKeepAliveLocks()
        registerScreenOffWatcher()
        TouchAccessibilityService.instance?.updateKeepAwakeOverlay(true, isDimScreenActive)

        val projectionMgr = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val projection = projectionMgr.getMediaProjection(resultCode, resultData) ?: run {
            Log.e(TAG, "Falha ao obter MediaProjection")
            stopSelf()
            return
        }
        projection.registerCallback(projectionCallback, null)
        mediaProjection = projection

        val server = MirrorStreamingServer(
            port = MirrorProtocol.TCP_STREAM_PORT,
            onClientConnectionChanged = { connected, clientIp ->
                if (connected) {
                    if (isReceiverOnlyAudio) {
                        applyPhoneSpeakerSilenceForReceiverOnly()
                    }
                    // Se o usuário liberou WRITE_SETTINGS, escurece automaticamente a tela do celular para 0%
                    // ao conectar na T3L (sem apertar o botão Power e sem desligar a multimídia!)
                    if (Settings.System.canWrite(this@ScreenCaptureService)) {
                        setDimPhoneScreen(true)
                    }
                } else {
                    restorePhoneSpeakerVolume()
                    setDimPhoneScreen(false)
                }
                _serviceState.value = _serviceState.value.copy(
                    isReceiverConnected = connected,
                    connectedReceiverIp = clientIp
                )
            },
            onClientReadyForStream = {
                val enc = videoEncoder ?: return@MirrorStreamingServer
                val srv = streamingServer ?: return@MirrorStreamingServer
                // 1. Envia resolução e FPS atuais
                srv.sendStreamMetadata(enc.currentProfile)
                // 2. Informa status do Touchback, Áudio e Tela Escura para o painel da T3L
                srv.sendTouchbackStatus(
                    TouchAccessibilityService.isServiceEnabled(),
                    isRemoteAudioMuted,
                    isDimScreenActive
                )
                // 3. Envia imediatamente o SPS/PPS (CSD-0) em cache
                enc.cachedSpsPpsConfig?.let { spsPps ->
                    srv.enqueuePacket(MirrorProtocol.TYPE_VIDEO_CONFIG, 0L, spsPps, spsPps.size)
                }
                // 4. Força geração imediata de um IDR Keyframe
                enc.requestInstantIdrFrame()
            },
            onTouchCommandReceived = { action, normX, normY, _ ->
                if (action == MirrorProtocol.TOUCH_ACTION_DOWN) {
                    wakeUpDisplayForContinuousMirroring()
                }
                val profile = videoEncoder?.currentProfile ?: initialProfile
                TouchAccessibilityService.instance?.handleRemoteTouch(
                    action = action,
                    normX = normX,
                    normY = normY,
                    streamWidth = profile.width,
                    streamHeight = profile.height
                )
            },
            onRequestIdrFrame = {
                videoEncoder?.cachedSpsPpsConfig?.let { spsPps ->
                    streamingServer?.enqueuePacket(MirrorProtocol.TYPE_VIDEO_CONFIG, 0L, spsPps, spsPps.size)
                }
                videoEncoder?.requestInstantIdrFrame()
            },
            onRequestProfileChange = { requestedProfile ->
                applyNewStreamProfile(requestedProfile)
            },
            onSystemNavCommand = { navAction ->
                TouchAccessibilityService.instance?.performSystemNavigation(navAction)
            },
            onMediaControlCommand = { mediaAction ->
                handleRemoteMediaControl(mediaAction)
            },
            onLaunchAppCommand = { appId ->
                handleRemoteLaunchApp(appId)
            },
            onStopRemoteCommand = {
                stopMirroringAndSelf()
            },
            onAdaptiveBitrateChange = { newBitrateBps ->
                videoEncoder?.updateBitrate(newBitrateBps)
            },
            onTelemetryUpdated = { rttMs, bitrateKbps, fps ->
                _serviceState.value = _serviceState.value.copy(
                    rttMs = rttMs,
                    activeBitrateKbps = bitrateKbps,
                    liveFps = fps
                )
            }
        )
        server.setProfileTargetBitrate(initialProfile.bitrateBps)
        server.start()
        streamingServer = server

        TouchAccessibilityService.onAvailabilityChanged = { enabled ->
            if (enabled && _serviceState.value.isStreaming) {
                TouchAccessibilityService.instance?.updateKeepAwakeOverlay(true, isDimScreenActive)
            }
            streamingServer?.sendTouchbackStatus(enabled, isRemoteAudioMuted, isDimScreenActive)
        }

        val encoder = H264VideoEncoder(
            mediaProjection = projection,
            onEncodedPacket = { packetType, ptsUs, data, length ->
                server.enqueuePacket(packetType, ptsUs, data, length)
            }
        )
        encoder.start(initialProfile)
        videoEncoder = encoder

        val audio = InternalAudioCapturer(
            mediaProjection = projection,
            onAudioChunk = { ptsUs, pcmData, length ->
                if (!isRemoteAudioMuted) {
                    server.enqueuePacket(MirrorProtocol.TYPE_AUDIO_PCM, ptsUs, pcmData, length)
                }
            }
        ).apply {
            agcBoostEnabled = isReceiverOnlyAudio
        }
        audio.start()
        audioCapturer = audio

        val discovery = UdpDiscoveryManager(
            context = this,
            tcpPort = MirrorProtocol.TCP_STREAM_PORT,
            getCurrentProfile = { videoEncoder?.currentProfile ?: initialProfile }
        )
        discovery.start(serviceScope)
        discoveryManager = discovery

        _serviceState.value = SenderServiceState(
            isStreaming = true,
            isReceiverConnected = false,
            connectedReceiverIp = null,
            activeProfile = initialProfile,
            localIps = UdpDiscoveryManager.getLocalIpAddresses(),
            isDimScreenActive = isDimScreenActive,
            isReceiverOnlyAudio = isReceiverOnlyAudio
        )
    }

    private fun handleRemoteMediaControl(mediaAction: Int) {
        try {
            val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
            when (mediaAction) {
                1 -> dispatchMediaKey(am, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
                2 -> dispatchMediaKey(am, KeyEvent.KEYCODE_MEDIA_NEXT)
                3 -> dispatchMediaKey(am, KeyEvent.KEYCODE_MEDIA_PREVIOUS)
                4 -> am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_RAISE, AudioManager.FLAG_SHOW_UI)
                5 -> am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_LOWER, AudioManager.FLAG_SHOW_UI)
                6 -> {
                    isRemoteAudioMuted = !isRemoteAudioMuted
                    streamingServer?.sendTouchbackStatus(
                        TouchAccessibilityService.isServiceEnabled(),
                        isRemoteAudioMuted,
                        isDimScreenActive
                    )
                }
                7 -> {
                    toggleDimPhoneScreen()
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Erro ao executar controle remoto de mídia: ${e.message}")
        }
    }

    private fun toggleDimPhoneScreen() {
        setDimPhoneScreen(!isDimScreenActive)
    }

    @Synchronized
    private fun setDimPhoneScreen(enable: Boolean) {
        isDimScreenActive = enable
        _serviceState.value = _serviceState.value.copy(isDimScreenActive = enable)

        if (enable) {
            applyMinimumSystemBrightness()
        } else {
            restoreSystemBrightness()
        }

        if (_serviceState.value.isStreaming) {
            TouchAccessibilityService.instance?.updateKeepAwakeOverlay(true, dimPhoneScreen = enable)
            streamingServer?.sendTouchbackStatus(
                TouchAccessibilityService.isServiceEnabled(),
                isRemoteAudioMuted,
                isDimScreenActive
            )
        }
    }

    /**
     * Reduz o brilho físico do painel AMOLED do celular para 0/255 via WRITE_SETTINGS
     * sem bloquear o dispositivo nem encerrar o MediaProjection (mantém a T3L 100% brilhante!).
     */
    @Synchronized
    private fun applyMinimumSystemBrightness() {
        try {
            if (!Settings.System.canWrite(this)) return
            val cr = contentResolver
            if (savedSystemBrightnessMode < 0) {
                savedSystemBrightnessMode = Settings.System.getInt(
                    cr,
                    Settings.System.SCREEN_BRIGHTNESS_MODE,
                    Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC
                )
            }
            if (savedSystemBrightness < 0) {
                savedSystemBrightness = Settings.System.getInt(
                    cr,
                    Settings.System.SCREEN_BRIGHTNESS,
                    128
                )
            }
            Settings.System.putInt(
                cr,
                Settings.System.SCREEN_BRIGHTNESS_MODE,
                Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL
            )
            Settings.System.putInt(cr, Settings.System.SCREEN_BRIGHTNESS, 0)
            Log.i(TAG, "Brilho físico do celular reduzido para 0/255 (salvo=$savedSystemBrightness, modo=$savedSystemBrightnessMode)")
        } catch (e: Exception) {
            Log.w(TAG, "Aviso ao aplicar brilho 0/255: ${e.message}")
        }
    }

    @Synchronized
    private fun restoreSystemBrightness() {
        if (savedSystemBrightness < 0 && savedSystemBrightnessMode < 0) return
        try {
            if (Settings.System.canWrite(this)) {
                val cr = contentResolver
                if (savedSystemBrightness >= 0) {
                    Settings.System.putInt(cr, Settings.System.SCREEN_BRIGHTNESS, savedSystemBrightness)
                }
                if (savedSystemBrightnessMode >= 0) {
                    Settings.System.putInt(cr, Settings.System.SCREEN_BRIGHTNESS_MODE, savedSystemBrightnessMode)
                }
                Log.i(TAG, "Brilho original do celular restaurado ($savedSystemBrightness / modo=$savedSystemBrightnessMode)")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Aviso ao restaurar brilho original: ${e.message}")
        } finally {
            savedSystemBrightness = -1
            savedSystemBrightnessMode = -1
        }
    }

    private fun dispatchMediaKey(am: AudioManager, keyCode: Int) {
        val now = SystemClock.uptimeMillis()
        am.dispatchMediaKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_DOWN, keyCode, 0))
        am.dispatchMediaKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_UP, keyCode, 0))
    }

    private fun handleRemoteLaunchApp(appId: Int) {
        val pkg = when (appId) {
            1 -> "com.waze"
            2 -> "com.google.android.apps.maps"
            3 -> "com.spotify.music"
            4 -> "com.google.android.youtube"
            5 -> "com.google.android.apps.youtube.music"
            else -> return
        }
        val launched = TouchAccessibilityService.instance?.launchAppByPackage(pkg) ?: false
        if (!launched) {
            try {
                val intent = packageManager.getLaunchIntentForPackage(pkg) ?: return
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                startActivity(intent)
            } catch (e: Exception) {
                Log.w(TAG, "Não foi possível abrir $pkg: ${e.message}")
            }
        }
    }

    private fun applyNewStreamProfile(newProfile: StreamProfile) {
        val enc = videoEncoder ?: return
        enc.reconfigureProfile(newProfile)
        streamingServer?.sendStreamMetadata(newProfile)
        enc.cachedSpsPpsConfig?.let { spsPps ->
            streamingServer?.enqueuePacket(MirrorProtocol.TYPE_VIDEO_CONFIG, 0L, spsPps, spsPps.size)
        }
        enc.requestInstantIdrFrame()
        _serviceState.value = _serviceState.value.copy(activeProfile = newProfile)
    }

    @Suppress("DEPRECATION")
    private fun acquireKeepAliveLocks() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        // SCREEN_DIM_WAKE_LOCK impede que a tela do celular apague sozinha por inatividade,
        // garantindo que o SurfaceFlinger continue enviando vídeo 100% vivo para a multimídia T3L!
        wakeLock = pm.newWakeLock(
            PowerManager.SCREEN_DIM_WAKE_LOCK or PowerManager.ON_AFTER_RELEASE,
            "CarMirrorSender::ContinuousScreenMirrorLock"
        ).apply {
            setReferenceCounted(false)
            acquire(8 * 60 * 60 * 1000L) // Até 8 horas contínuas
        }

        // Trava Wi-Fi em modo Low-Latency para o Hotspot não entrar em economia de energia
        try {
            val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                WifiManager.WIFI_MODE_FULL_LOW_LATENCY
            } else {
                WifiManager.WIFI_MODE_FULL_HIGH_PERF
            }
            wifiLock = wm.createWifiLock(mode, "CarMirrorSender::WifiLowLatencyLock").apply {
                setReferenceCounted(false)
                acquire()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Aviso ao adquirir WifiLock: ${e.message}")
        }
    }

    @Suppress("DEPRECATION")
    private fun wakeUpDisplayForContinuousMirroring() {
        try {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            if (!pm.isInteractive) {
                val tempWake = pm.newWakeLock(
                    PowerManager.SCREEN_DIM_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
                    "CarMirrorSender::InstantWakeLock"
                )
                tempWake.acquire(2500L)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Aviso ao reacender tela: ${e.message}")
        }
    }

    private fun registerScreenOffWatcher() {
        if (isScreenReceiverRegistered) return
        try {
            val filter = IntentFilter(Intent.ACTION_SCREEN_OFF)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(screenOffReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                registerReceiver(screenOffReceiver, filter)
            }
            isScreenReceiverRegistered = true
        } catch (e: Exception) {
            Log.w(TAG, "Erro ao registrar screenOffReceiver: ${e.message}")
        }
    }

    private fun unregisterScreenOffWatcher() {
        if (!isScreenReceiverRegistered) return
        try {
            unregisterReceiver(screenOffReceiver)
        } catch (_: Exception) {}
        isScreenReceiverRegistered = false
    }

    private fun toggleReceiverOnlyAudioMode() {
        isReceiverOnlyAudio = !isReceiverOnlyAudio
        audioCapturer?.agcBoostEnabled = isReceiverOnlyAudio
        _serviceState.value = _serviceState.value.copy(isReceiverOnlyAudio = isReceiverOnlyAudio)
        if (_serviceState.value.isReceiverConnected) {
            if (isReceiverOnlyAudio) {
                applyPhoneSpeakerSilenceForReceiverOnly()
            } else {
                restorePhoneSpeakerVolume()
            }
        }
    }

    /**
     * Silencia o alto-falante do celular colocando STREAM_MUSIC no nível mínimo não-zero (1).
     * Por que nível 1 e não 0? No Samsung OneUI / Android 14+, o volume 0 zera o mixer de hardware
     * e silencia também o AudioPlaybackCapture. Com volume 1 (inaudível no carro) + nosso AGC
     * digital no InternalAudioCapturer, o áudio chega em 100% de volume limpo SOMENTE na central T3L!
     */
    @Synchronized
    private fun applyPhoneSpeakerSilenceForReceiverOnly() {
        try {
            val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val currentVol = am.getStreamVolume(AudioManager.STREAM_MUSIC)
            if (savedPhoneMusicVolume < 0 && currentVol > 1) {
                savedPhoneMusicVolume = currentVol
            }
            if (currentVol != 1) {
                am.setStreamVolume(AudioManager.STREAM_MUSIC, 1, 0)
            }
            audioCapturer?.agcBoostEnabled = true
            Log.i(TAG, "Modo Som Só no Receptor ativado (volume anterior salvo=$savedPhoneMusicVolume, celular em nível mínimo=1 + AGC)")
        } catch (e: Exception) {
            Log.w(TAG, "Aviso ao ajustar volume silencioso do celular: ${e.message}")
        }
    }

    @Synchronized
    private fun restorePhoneSpeakerVolume() {
        val original = savedPhoneMusicVolume
        if (original < 0) return
        savedPhoneMusicVolume = -1
        try {
            val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
            am.setStreamVolume(AudioManager.STREAM_MUSIC, original, 0)
            Log.i(TAG, "Volume original do celular restaurado para $original")
        } catch (e: Exception) {
            Log.w(TAG, "Aviso ao restaurar volume original do celular: ${e.message}")
        }
    }

    private fun stopActiveComponents() {
        restorePhoneSpeakerVolume()
        restoreSystemBrightness()
        unregisterScreenOffWatcher()
        isDimScreenActive = false
        TouchAccessibilityService.instance?.updateKeepAwakeOverlay(false, false)
        TouchAccessibilityService.onAvailabilityChanged = null
        discoveryManager?.stop()
        discoveryManager = null

        audioCapturer?.stop()
        audioCapturer = null

        videoEncoder?.stop()
        videoEncoder = null

        streamingServer?.stop()
        streamingServer = null

        try {
            mediaProjection?.unregisterCallback(projectionCallback)
            mediaProjection?.stop()
        } catch (_: Exception) {}
        mediaProjection = null

        try {
            if (wakeLock?.isHeld == true) {
                wakeLock?.release()
            }
        } catch (_: Exception) {}
        wakeLock = null

        try {
            if (wifiLock?.isHeld == true) {
                wifiLock?.release()
            }
        } catch (_: Exception) {}
        wifiLock = null
    }

    private fun stopMirroringAndSelf() {
        stopActiveComponents()
        _serviceState.value = SenderServiceState(isStreaming = false)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        stopActiveComponents()
        serviceScope.cancel()
        _serviceState.value = SenderServiceState(isStreaming = false)
        super.onDestroy()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "CarMirror Transmissão Automotiva",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Mantém o espelhamento de tela ativo para a central multimídia"
            }
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(contentText: String): Notification {
        val openIntent = Intent(this, SenderMainActivity::class.java)
        val pendingOpen = PendingIntent.getActivity(
            this,
            0,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = Intent(this, ScreenCaptureService::class.java).apply {
            action = ACTION_STOP_CAPTURE
        }
        val pendingStop = PendingIntent.getService(
            this,
            1,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val dimIntent = Intent(this, ScreenCaptureService::class.java).apply {
            action = ACTION_TOGGLE_DIM_SCREEN
        }
        val pendingDim = PendingIntent.getService(
            this,
            2,
            dimIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("CarMirror Pro — Espelhamento Ativo")
            .setContentText(contentText)
            .setSmallIcon(android.R.drawable.ic_menu_share)
            .setContentIntent(pendingOpen)
            .setOngoing(true)
            .addAction(android.R.drawable.ic_menu_view, "Tela 0.5%", pendingDim)
            .addAction(android.R.drawable.ic_media_pause, "Parar", pendingStop)
            .build()
    }
}
