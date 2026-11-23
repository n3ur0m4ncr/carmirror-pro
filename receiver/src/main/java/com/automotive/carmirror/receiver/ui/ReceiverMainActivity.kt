package com.automotive.carmirror.receiver.ui

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.util.TypedValue
import android.view.*
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.automotive.carmirror.receiver.R
import com.automotive.carmirror.receiver.ota.OtaUpdateInfo
import com.automotive.carmirror.receiver.ota.OtaUpdateManager
import com.automotive.carmirror.receiver.protocol.DiscoveredSender
import com.automotive.carmirror.receiver.protocol.MirrorProtocol
import com.automotive.carmirror.receiver.protocol.UdpDiscoveryClient
import com.automotive.carmirror.receiver.streaming.H264HardwareDecoder
import com.automotive.carmirror.receiver.streaming.LowLatencyAudioPlayer
import com.automotive.carmirror.receiver.streaming.MirrorStreamingClient
import kotlinx.coroutines.launch

/**
 * Cockpit Automotivo Profissional da Central Multimídia (Allwinner T3L - Android 12 API 31 - 2GB RAM).
 * - Orientação Landscape fixa, Tela Cheia Imersiva (Immersive Sticky) e footprint mínimo de RAM.
 * - Tela de conexão Widescreen em 2 colunas com Relógio Digital Automotivo, Radar de Descoberta e Botão Único "CONECTAR".
 * - Dock flutuante estilo CarPlay/Coolwalk para controlar apps, mídia, áudio, navegação e qualidade do celular.
 */
class ReceiverMainActivity : AppCompatActivity(), SurfaceHolder.Callback {

    private lateinit var videoSurfaceView: SurfaceView
    private lateinit var connectionOverlay: LinearLayout
    private lateinit var radarPulseDot: View
    private lateinit var radarPillText: TextView
    private lateinit var discoveredDeviceTitle: TextView
    private lateinit var discoveredDeviceMeta: TextView
    private lateinit var statusTextView: TextView
    private lateinit var singleConnectButton: Button
    private lateinit var otaUpdateButton: Button

    private lateinit var floatingNavBar: LinearLayout
    private lateinit var navTogglePill: TextView
    private lateinit var touchbackBadge: TextView
    private lateinit var telemetryBadge: TextView
    private lateinit var audioMuteBtn: Button
    private lateinit var dimPhoneBtn: Button

    private lateinit var discoveryClient: UdpDiscoveryClient
    private lateinit var otaUpdateManager: OtaUpdateManager
    private lateinit var videoDecoder: H264HardwareDecoder
    private lateinit var audioPlayer: LowLatencyAudioPlayer
    private lateinit var streamingClient: MirrorStreamingClient

    private var pulseAnimator: ObjectAnimator? = null

    @Volatile
    private var latestDiscoveredSender: DiscoveredSender? = null

    @Volatile
    private var latestOtaOffer: OtaUpdateInfo? = null

    @Volatile
    private var isOtaDownloading = false

    // Auto-Connect imediato estilo CarPlay/Android Auto Wireless (conecta sozinho em <1s!)
    @Volatile
    private var autoConnectRequested = true

    private var lastMoveSentTimeMs = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        videoDecoder = H264HardwareDecoder(
            onRequestKeyframe = {
                streamingClient.requestKeyframe()
            }
        )
        audioPlayer = LowLatencyAudioPlayer(this)
        discoveryClient = UdpDiscoveryClient(this)
        otaUpdateManager = OtaUpdateManager(this)

        streamingClient = MirrorStreamingClient(
            videoDecoder = videoDecoder,
            audioPlayer = audioPlayer,
            getActiveWifiNetwork = { discoveryClient.activeWifiNetwork },
            onConnectionStateChanged = { connected, message ->
                runOnUiThread {
                    statusTextView.text = message
                    connectionOverlay.visibility = if (connected) View.GONE else View.VISIBLE
                    if (connected) {
                        enforceImmersiveFullscreen()
                    } else if (autoConnectRequested) {
                        radarPillText.text = "AUTO-CONEXÃO RÁPIDA ATIVA"
                        radarPillText.setTextColor(Color.parseColor("#FBBF24"))
                        setRadarDotColor("#F59E0B")
                    }
                }
            },
            onStreamResolutionChanged = { w, h, _ ->
                videoDecoder.updateResolutionAndConfig(w, h, null)
            },
            onRemoteStatusChanged = { touchbackEnabled, audioMuted, dimPhoneScreen ->
                runOnUiThread {
                    if (touchbackEnabled) {
                        touchbackBadge.text = "● Touchback ATIVO"
                        touchbackBadge.setTextColor(Color.parseColor("#34D399"))
                        touchbackBadge.background = createBorderedRoundedDrawable("#0B2A22", "#10B981", 1, dp(10))
                    } else {
                        touchbackBadge.text = "● Ativar Touchback no Celular"
                        touchbackBadge.setTextColor(Color.parseColor("#FBBF24"))
                        touchbackBadge.background = createBorderedRoundedDrawable("#2A1E0A", "#D97706", 1, dp(10))
                    }
                    if (audioMuted) {
                        audioMuteBtn.text = "🔇 MUDO"
                        audioMuteBtn.background = createBorderedRoundedDrawable("#3B1219", "#E11D48", 1, dp(10))
                    } else {
                        audioMuteBtn.text = "🔊 Áudio ON"
                        audioMuteBtn.background = createBorderedRoundedDrawable("#0B2A22", "#10B981", 1, dp(10))
                    }
                    if (dimPhoneScreen) {
                        dimPhoneBtn.text = "🌙 Tela 0%"
                        dimPhoneBtn.background = createBorderedRoundedDrawable("#0F2942", "#38BDF8", 1, dp(10))
                    } else {
                        dimPhoneBtn.text = "☀ Tela Celular"
                        dimPhoneBtn.background = createBorderedRoundedDrawable("#152036", "#334155", 1, dp(10))
                    }
                }
            },
            onTelemetryReceived = { rttMs, bitrateKbps, fps ->
                runOnUiThread {
                    val mbps = String.format("%.1f", bitrateKbps / 1000.0)
                    telemetryBadge.text = "⚡ ${rttMs}ms • ${mbps}M • ${fps}fps"
                }
            }
        )

        setContentView(buildAutomotiveCockpitUi())
        startRadarPulseAnimation()
        startOtaListener()
        enforceImmersiveFullscreen()
    }

    override fun onResume() {
        super.onResume()
        enforceImmersiveFullscreen()
        startAutoDiscovery()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            enforceImmersiveFullscreen()
        }
    }

    override fun onDestroy() {
        otaUpdateManager.stopListening()
        pulseAnimator?.cancel()
        pulseAnimator = null
        discoveryClient.stopDiscovery()
        streamingClient.disconnect()
        videoDecoder.release()
        audioPlayer.stop()
        super.onDestroy()
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun buildAutomotiveCockpitUi(): View {
        val rootFrame = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
        }

        // 1. SurfaceView dedicada ao Hardware H.264 Decoder da T3L
        videoSurfaceView = SurfaceView(this).apply {
            holder.addCallback(this@ReceiverMainActivity)
            setOnTouchListener { view, event ->
                if (floatingNavBar.visibility == View.VISIBLE && event.actionMasked == MotionEvent.ACTION_DOWN) {
                    floatingNavBar.visibility = View.GONE
                }
                handleCapacitiveTouch(view, event)
                true
            }
        }
        rootFrame.addView(
            videoSurfaceView,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
                Gravity.CENTER
            )
        )

        // 2. Tela Inicial Cockpit Widescreen (2 Colunas otimizadas para 1024x600 / 1280x720)
        connectionOverlay = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(dp(36), dp(28), dp(36), dp(28))
            background = GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                intArrayOf(
                    Color.parseColor("#FA050914"),
                    Color.parseColor("#FA0C162C"),
                    Color.parseColor("#FA070B16")
                )
            )
        }

        // ---------- COLUNA ESQUERDA: RELÓGIO AUTOMOTIVO & IDENTIDADE ----------
        val leftPane = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, dp(28), 0)
        }

        val brandRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, dp(18))
        }
        val logoView = ImageView(this).apply {
            setImageResource(R.drawable.ic_launcher_receiver)
        }
        brandRow.addView(logoView, LinearLayout.LayoutParams(dp(46), dp(46)).apply {
            marginEnd = dp(12)
        })
        val brandTexts = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        brandTexts.addView(TextView(this).apply {
            text = "CarMirror T3L"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        })
        brandTexts.addView(TextView(this).apply {
            text = "Wireless H.264 Cockpit • v2.0 PRO"
            setTextColor(Color.parseColor("#38BDF8"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        })
        brandRow.addView(brandTexts)
        leftPane.addView(brandRow)

        // Relógio Digital Automotivo Grande
        val clockView = TextClock(this).apply {
            format24Hour = "HH:mm"
            format12Hour = "HH:mm"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 68f)
            typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
            letterSpacing = -0.03f
        }
        leftPane.addView(clockView)

        val dateView = TextClock(this).apply {
            format24Hour = "EEEE, dd 'de' MMMM"
            format12Hour = "EEEE, dd 'de' MMMM"
            setTextColor(Color.parseColor("#94A3B8"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            setPadding(dp(4), 0, 0, dp(24))
        }
        leftPane.addView(dateView)

        // Badges de Hardware Otimizado (Allwinner T3L)
        val hwRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        hwRow.addView(createHardwareChip("Okio ABR"))
        hwRow.addView(createHardwareChip("HW VPU"))
        hwRow.addView(createHardwareChip("Auto-Connect"))
        leftPane.addView(hwRow)

        connectionOverlay.addView(
            leftPane,
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1.05f)
        )

        // ---------- COLUNA DIREITA: CARD DE CONEXÃO GLASSMORPHISM ----------
        val rightCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(26), dp(24), dp(26), dp(24))
            background = createBorderedRoundedDrawable("#CC111B30", "#1E3A5F", 1, dp(22))
        }

        val radarRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, dp(14))
        }
        radarPulseDot = View(this).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#38BDF8"))
            }
        }
        radarRow.addView(radarPulseDot, LinearLayout.LayoutParams(dp(10), dp(10)).apply {
            marginEnd = dp(8)
        })
        radarPillText = TextView(this).apply {
            text = "PROCURANDO SMARTPHONE NA REDE..."
            setTextColor(Color.parseColor("#38BDF8"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
            typeface = Typeface.DEFAULT_BOLD
            letterSpacing = 0.08f
        }
        radarRow.addView(radarPillText)
        rightCard.addView(radarRow)

        discoveredDeviceTitle = TextView(this).apply {
            text = "Aguardando CarMirror Sender"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 21f)
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        }
        rightCard.addView(discoveredDeviceTitle)

        discoveredDeviceMeta = TextView(this).apply {
            text = "Conecte o Wi-Fi da central no Hotspot do seu celular e inicie o transmissor."
            setTextColor(Color.parseColor("#94A3B8"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f)
            setPadding(0, dp(6), 0, dp(12))
            setLineSpacing(dp(2).toFloat(), 1f)
        }
        rightCard.addView(discoveredDeviceMeta)

        statusTextView = TextView(this).apply {
            text = "Descoberta automática (UDP :49150) ativa"
            setTextColor(Color.parseColor("#64748B"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
            typeface = Typeface.MONOSPACE
            setPadding(0, 0, 0, dp(22))
        }
        rightCard.addView(statusTextView)

        singleConnectButton = Button(this).apply {
            text = "▶   CONECTAR AGORA"
            isAllCaps = false
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            letterSpacing = 0.04f
            elevation = dp(8).toFloat()
            background = createGradientRippleDrawable("#059669", "#0284C7", dp(16))
            setPadding(dp(24), dp(16), dp(24), dp(16))
            setOnClickListener { onSingleConnectClicked() }
        }
        rightCard.addView(
            singleConnectButton,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )

        otaUpdateButton = Button(this).apply {
            text = "☁️  Verificar Atualização (GitHub / PC)"
            isAllCaps = false
            setTextColor(Color.parseColor("#94A3B8"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f)
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            background = createBorderedRippleDrawable("#101A2E", "#243554", dp(12))
            setPadding(dp(16), dp(11), dp(16), dp(11))
            setOnClickListener {
                val offer = latestOtaOffer
                if (offer != null && offer.isNewer) {
                    startOtaDownload(offer)
                } else {
                    checkGitHubAndShowStatus()
                }
            }
        }
        rightCard.addView(
            otaUpdateButton,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(12) }
        )

        connectionOverlay.addView(
            rightCard,
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        )

        rootFrame.addView(
            connectionOverlay,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )

        // 3. Dock Flutuante Automotivo (CarPlay / Coolwalk Style) para controlar o Celular
        val navContainer = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        navTogglePill = TextView(this).apply {
            text = "☰"
            setTextColor(Color.parseColor("#E2E8F0"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            background = createBorderedRoundedDrawable("#B30B1324", "#38BDF8", 1, dp(16))
            setPadding(dp(12), dp(22), dp(12), dp(22))
            setOnClickListener {
                floatingNavBar.visibility =
                    if (floatingNavBar.visibility == View.VISIBLE) View.GONE else View.VISIBLE
            }
        }
        navContainer.addView(navTogglePill)

        floatingNavBar = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            background = createBorderedRoundedDrawable("#F00A101F", "#25395C", 1, dp(18))
            setPadding(dp(14), dp(12), dp(14), dp(12))
            elevation = dp(12).toFloat()

            // Cabeçalho do Dock com status Touchback + Fechar
            val dockHeader = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, 0, 0, dp(8))
            }
            touchbackBadge = TextView(context).apply {
                text = "● Touchback..."
                setTextColor(Color.parseColor("#94A3B8"))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
                typeface = Typeface.DEFAULT_BOLD
                background = createBorderedRoundedDrawable("#162033", "#243554", 1, dp(10))
                setPadding(dp(10), dp(4), dp(10), dp(4))
            }
            dockHeader.addView(touchbackBadge, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { marginEnd = dp(8) })

            telemetryBadge = TextView(context).apply {
                text = "⚡ Okio ABR"
                setTextColor(Color.parseColor("#38BDF8"))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
                typeface = Typeface.MONOSPACE
                background = createBorderedRoundedDrawable("#0F2942", "#1E4976", 1, dp(10))
                setPadding(dp(8), dp(4), dp(8), dp(4))
            }
            dockHeader.addView(telemetryBadge, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { marginEnd = dp(10) })

            val spacer = View(context)
            dockHeader.addView(spacer, LinearLayout.LayoutParams(0, 1, 1f))

            val closeBtn = TextView(context).apply {
                text = "✕"
                setTextColor(Color.parseColor("#94A3B8"))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                typeface = Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER
                background = createBorderedRoundedDrawable("#182235", "#2B3C5A", 1, dp(10))
                setPadding(dp(10), dp(4), dp(10), dp(4))
                setOnClickListener { visibility = View.GONE }
            }
            dockHeader.addView(closeBtn)
            addView(dockHeader)

            // Seção 1: Apps Automotivos no Celular (Cores da marca)
            addView(createDockSectionLabel("ATALHOS DO CELULAR"))
            addView(createButtonRow(
                createDockTileBtn("Waze", "#0E2D3D", "#33CCFF") { streamingClient.launchRemoteApp(1) },
                createDockTileBtn("Maps", "#0E2B1E", "#34A853") { streamingClient.launchRemoteApp(2) },
                createDockTileBtn("Spotify", "#0C2918", "#1DB954") { streamingClient.launchRemoteApp(3) },
                createDockTileBtn("YouTube", "#361116", "#FF3B30") { streamingClient.launchRemoteApp(4) },
                createDockTileBtn("YT Music", "#381020", "#F43F5E") { streamingClient.launchRemoteApp(5) }
            ))

            // Seção 2: Mídia & Áudio do Celular
            addView(createDockSectionLabel("MÍDIA, ÁUDIO E TELA DO CELULAR"))
            audioMuteBtn = createDockTileBtn("🔊 Áudio ON", "#0B2A22", "#10B981", autoClose = false) {
                streamingClient.sendMediaControl(6)
            }
            dimPhoneBtn = createDockTileBtn("☀ Tela Celular", "#152036", "#334155", autoClose = false) {
                streamingClient.sendMediaControl(7)
            }
            addView(createButtonRow(
                createDockTileBtn("⏮", "#152036", "#334155", autoClose = false) { streamingClient.sendMediaControl(3) },
                createDockTileBtn("⏯", "#0F2942", "#38BDF8", autoClose = false) { streamingClient.sendMediaControl(1) },
                createDockTileBtn("⏭", "#152036", "#334155", autoClose = false) { streamingClient.sendMediaControl(2) },
                createDockTileBtn("🔉", "#152036", "#334155", autoClose = false) { adjustLocalCarVolume(AudioManager.ADJUST_LOWER) },
                createDockTileBtn("🔊", "#152036", "#334155", autoClose = false) { adjustLocalCarVolume(AudioManager.ADJUST_RAISE) },
                audioMuteBtn,
                dimPhoneBtn
            ))

            // Seção 3: Navegação & Qualidade do Transmissor
            addView(createDockSectionLabel("SISTEMA E QUALIDADE"))
            addView(createButtonRow(
                createDockTileBtn("◁ Voltar", "#152036", "#334155") { streamingClient.sendSystemNavigation(1) },
                createDockTileBtn("○ Home", "#152036", "#334155") { streamingClient.sendSystemNavigation(2) },
                createDockTileBtn("□ Apps", "#152036", "#334155") { streamingClient.sendSystemNavigation(3) },
                createDockTileBtn("600p 30", "#111C30", "#2A4365") { streamingClient.requestProfileChange(0) },
                createDockTileBtn("720p 30", "#111C30", "#2A4365") { streamingClient.requestProfileChange(2) },
                createDockTileBtn("⛔ Parar", "#3B1219", "#DC2626") { streamingClient.stopRemoteSender() }
            ))
        }
        navContainer.addView(floatingNavBar, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { marginStart = dp(8) })

        rootFrame.addView(
            navContainer,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER_VERTICAL or Gravity.START
            ).apply {
                marginStart = dp(8)
            }
        )

        return rootFrame
    }

    private fun startOtaListener() {
        // 1. Verifica automaticamente novas versões no GitHub Releases ao abrir o app na multimídia T3L
        lifecycleScope.launch {
            val ghOffer = otaUpdateManager.checkGitHubRelease()
            if (ghOffer != null) {
                applyDetectedOtaOffer(ghOffer)
            }
        }

        // 2. Continua escutando o servidor local do PC via Wi-Fi (UDP 49151)
        otaUpdateManager.startListeningLocalPc(lifecycleScope) { localOffer ->
            runOnUiThread {
                if (localOffer.isNewer || latestOtaOffer == null) {
                    applyDetectedOtaOffer(localOffer)
                }
            }
        }
    }

    private fun applyDetectedOtaOffer(offer: OtaUpdateInfo) {
        latestOtaOffer = offer
        if (offer.isNewer) {
            otaUpdateButton.text = "🚀  NOVA VERSÃO v${offer.remoteVersionName} (${offer.sourceLabel}) — INSTALAR"
            otaUpdateButton.setTextColor(Color.WHITE)
            otaUpdateButton.background = createGradientRippleDrawable("#0284C7", "#10B981", dp(12))
        } else {
            otaUpdateButton.text = "☁️  Atualizado (v${offer.remoteVersionName}) • Verificar"
            otaUpdateButton.setTextColor(Color.parseColor("#34D399"))
            otaUpdateButton.background = createBorderedRippleDrawable("#0B2A22", "#10B981", dp(12))
        }
    }

    private fun checkGitHubAndShowStatus() {
        otaUpdateButton.text = "☁️  Verificando GitHub..."
        lifecycleScope.launch {
            val offer = otaUpdateManager.checkGitHubRelease()
            if (offer != null) {
                applyDetectedOtaOffer(offer)
                if (offer.isNewer) {
                    startOtaDownload(offer)
                } else {
                    AlertDialog.Builder(this@ReceiverMainActivity)
                        .setTitle("CarMirror T3L Atualizado")
                        .setMessage("Você já está na versão mais recente do GitHub (v${offer.remoteVersionName}).")
                        .setPositiveButton("OK", null)
                        .setNeutralButton("Usar PC Local (IP)") { _, _ -> showManualOtaIpDialog() }
                        .show()
                }
            } else {
                otaUpdateButton.text = "☁️  Verificar Atualização (GitHub / PC)"
                showManualOtaIpDialog()
            }
        }
    }

    private fun startOtaDownload(offer: OtaUpdateInfo) {
        if (isOtaDownloading) return
        isOtaDownloading = true
        otaUpdateButton.isEnabled = false
        otaUpdateButton.text = "⬇  BAIXANDO (${offer.sourceLabel})... 0%"

        lifecycleScope.launch {
            otaUpdateManager.downloadAndInstall(
                info = offer,
                onProgress = { pct ->
                    otaUpdateButton.text = if (pct >= 100) "✓  ABRINDO INSTALADOR..." else "⬇  BAIXANDO... $pct%"
                },
                onNeedInstallPermission = {
                    isOtaDownloading = false
                    otaUpdateButton.isEnabled = true
                    otaUpdateButton.text = "🚀  INSTALAR v${offer.remoteVersionName}"
                    Toast.makeText(
                        this@ReceiverMainActivity,
                        "Ative 'Permitir desta fonte' na T3L e toque em Atualizar novamente.",
                        Toast.LENGTH_LONG
                    ).show()
                },
                onError = { err ->
                    isOtaDownloading = false
                    otaUpdateButton.isEnabled = true
                    otaUpdateButton.text = "🔄  TENTAR NOVAMENTE"
                    Toast.makeText(this@ReceiverMainActivity, err, Toast.LENGTH_LONG).show()
                }
            )
            isOtaDownloading = false
            otaUpdateButton.isEnabled = true
            otaUpdateButton.text = "🚀  INSTALAR v${offer.remoteVersionName}"
        }
    }

    private fun showManualOtaIpDialog() {
        val ipInput = EditText(this).apply {
            hint = "IP do PC (ex: 192.168.1.100)"
            setSingleLine(true)
        }
        AlertDialog.Builder(this)
            .setTitle("Atualizar T3L via PC Local (Wi-Fi)")
            .setMessage("Sem internet no momento ou deseja enviar direto do PC local? Digite o IP exibido no ENVIAR_ATUALIZACAO_WIFI.bat:")
            .setView(ipInput)
            .setPositiveButton("Baixar do PC") { _, _ ->
                val ip = ipInput.text.toString().trim()
                if (ip.isNotEmpty()) {
                    lifecycleScope.launch {
                        val info = otaUpdateManager.checkLocalPcManually(ip)
                        if (info != null) {
                            applyDetectedOtaOffer(info)
                            startOtaDownload(info)
                        } else {
                            Toast.makeText(this@ReceiverMainActivity, "PC não encontrado em $ip:8765", Toast.LENGTH_LONG).show()
                        }
                    }
                }
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun adjustLocalCarVolume(direction: Int) {
        try {
            val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
            am.adjustStreamVolume(AudioManager.STREAM_MUSIC, direction, AudioManager.FLAG_SHOW_UI)
        } catch (_: Exception) {}
    }

    private fun handleCapacitiveTouch(view: View, event: MotionEvent) {
        val w = view.width.toFloat()
        val h = view.height.toFloat()
        if (w <= 0f || h <= 0f) return

        val normX = (event.x / w).coerceIn(0.001f, 0.999f)
        val normY = (event.y / h).coerceIn(0.001f, 0.999f)

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                streamingClient.sendTouchEvent(MirrorProtocol.TOUCH_ACTION_DOWN, normX, normY)
            }

            MotionEvent.ACTION_MOVE -> {
                val now = System.currentTimeMillis()
                if (now - lastMoveSentTimeMs >= 16L) {
                    lastMoveSentTimeMs = now
                    streamingClient.sendTouchEvent(MirrorProtocol.TOUCH_ACTION_MOVE, normX, normY)
                }
            }

            MotionEvent.ACTION_UP -> {
                streamingClient.sendTouchEvent(MirrorProtocol.TOUCH_ACTION_UP, normX, normY)
            }

            MotionEvent.ACTION_CANCEL -> {
                streamingClient.sendTouchEvent(MirrorProtocol.TOUCH_ACTION_CANCEL, normX, normY)
            }
        }
    }

    private fun startAutoDiscovery() {
        discoveryClient.startDiscovery(lifecycleScope) { sender ->
            latestDiscoveredSender = sender
            saveLastSenderIp(sender.ipAddress)
            runOnUiThread {
                radarPillText.text = "SMARTPHONE DETECTADO ✓"
                radarPillText.setTextColor(Color.parseColor("#34D399"))
                setRadarDotColor("#10B981")

                discoveredDeviceTitle.text = sender.deviceName
                discoveredDeviceMeta.text = "IP ${sender.ipAddress}  •  Vídeo ${sender.width}×${sender.height} @ ${sender.fps}fps  •  Okio ABR"

                if (autoConnectRequested && !streamingClient.isConnectedNow()) {
                    streamingClient.connectWithAutoReconnect(sender.ipAddress, sender.tcpPort)
                }
            }
        }

        // Fast-Connect imediato: tenta conectar no Gateway do Hotspot ou último IP salvo sem esperar
        if (autoConnectRequested && !streamingClient.isSessionActive()) {
            val fastIp = discoveryClient.getWifiGatewayIp() ?: getLastSenderIp()
            if (!fastIp.isNullOrBlank()) {
                streamingClient.connectWithAutoReconnect(fastIp, MirrorProtocol.TCP_STREAM_PORT)
            }
        }
    }

    private fun saveLastSenderIp(ip: String) {
        try {
            getSharedPreferences("carmirror_prefs", Context.MODE_PRIVATE)
                .edit()
                .putString("last_sender_ip", ip)
                .apply()
        } catch (_: Exception) {}
    }

    private fun getLastSenderIp(): String? {
        return try {
            getSharedPreferences("carmirror_prefs", Context.MODE_PRIVATE)
                .getString("last_sender_ip", null)
        } catch (_: Exception) {
            null
        }
    }

    private fun onSingleConnectClicked() {
        autoConnectRequested = true
        val discovered = latestDiscoveredSender
        if (discovered != null) {
            streamingClient.connectWithAutoReconnect(discovered.ipAddress, discovered.tcpPort)
        } else {
            val gatewayIp = discoveryClient.getWifiGatewayIp()
            if (!gatewayIp.isNullOrBlank()) {
                statusTextView.text = "Conectando via Gateway Hotspot ($gatewayIp)..."
                streamingClient.connectWithAutoReconnect(gatewayIp, MirrorProtocol.TCP_STREAM_PORT)
            } else {
                statusTextView.text = "Aguardando sinal do CarMirror Sender..."
            }
        }
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        val sender = latestDiscoveredSender
        val w = sender?.width ?: 1024
        val h = sender?.height ?: 600
        videoDecoder.attachSurface(holder.surface, w, h)
        streamingClient.requestKeyframe()
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {}

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        videoDecoder.release()
    }

    @Suppress("DEPRECATION")
    private fun enforceImmersiveFullscreen() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.setDecorFitsSystemWindows(false)
            window.insetsController?.let { controller ->
                controller.hide(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())
                controller.systemBarsBehavior =
                    WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            window.decorView.systemUiVisibility = (
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                    or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_FULLSCREEN
                )
        }
    }

    private fun setRadarDotColor(hex: String) {
        (radarPulseDot.background as? GradientDrawable)?.setColor(Color.parseColor(hex))
    }

    private fun startRadarPulseAnimation() {
        pulseAnimator = ObjectAnimator.ofFloat(radarPulseDot, View.ALPHA, 1f, 0.2f).apply {
            duration = 900L
            repeatMode = ValueAnimator.REVERSE
            repeatCount = ValueAnimator.INFINITE
            start()
        }
    }

    private fun createHardwareChip(label: String): TextView {
        return TextView(this).apply {
            text = label
            setTextColor(Color.parseColor("#94A3B8"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            typeface = Typeface.MONOSPACE
            background = createBorderedRoundedDrawable("#111B30", "#1E2E4D", 1, dp(8))
            setPadding(dp(10), dp(5), dp(10), dp(5))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { marginEnd = dp(8) }
        }
    }

    private fun createDockSectionLabel(title: String): TextView {
        return TextView(this).apply {
            text = title
            setTextColor(Color.parseColor("#64748B"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
            typeface = Typeface.DEFAULT_BOLD
            letterSpacing = 0.08f
            setPadding(dp(2), dp(8), dp(2), dp(4))
        }
    }

    private fun createButtonRow(vararg buttons: View): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            buttons.forEachIndexed { index, btn ->
                val lp = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    if (index > 0) marginStart = dp(6)
                }
                addView(btn, lp)
            }
        }
    }

    private fun createDockTileBtn(
        label: String,
        fillHex: String,
        strokeHex: String,
        autoClose: Boolean = true,
        onClick: () -> Unit
    ): Button {
        return Button(this).apply {
            text = label
            isAllCaps = false
            minWidth = 0
            minimumWidth = 0
            minHeight = dp(38)
            minimumHeight = dp(38)
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            background = createBorderedRippleDrawable(fillHex, strokeHex, dp(10))
            setPadding(dp(12), dp(6), dp(12), dp(6))
            setOnClickListener {
                onClick()
                if (autoClose) {
                    floatingNavBar.visibility = View.GONE
                }
            }
        }
    }

    private fun createBorderedRoundedDrawable(
        fillHex: String,
        strokeHex: String,
        strokeDp: Int,
        radiusPx: Int
    ): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = radiusPx.toFloat()
            setColor(Color.parseColor(fillHex))
            setStroke(dp(strokeDp), Color.parseColor(strokeHex))
        }
    }

    private fun createBorderedRippleDrawable(fillHex: String, strokeHex: String, radiusPx: Int): RippleDrawable {
        val bg = createBorderedRoundedDrawable(fillHex, strokeHex, 1, radiusPx)
        return RippleDrawable(ColorStateList.valueOf(Color.parseColor("#33FFFFFF")), bg, null)
    }

    private fun createGradientRippleDrawable(startHex: String, endHex: String, radiusPx: Int): RippleDrawable {
        val grad = GradientDrawable(
            GradientDrawable.Orientation.LEFT_RIGHT,
            intArrayOf(Color.parseColor(startHex), Color.parseColor(endHex))
        ).apply {
            cornerRadius = radiusPx.toFloat()
        }
        return RippleDrawable(ColorStateList.valueOf(Color.parseColor("#44FFFFFF")), grad, null)
    }

    private fun dp(value: Int): Int {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            value.toFloat(),
            resources.displayMetrics
        ).toInt()
    }
}
