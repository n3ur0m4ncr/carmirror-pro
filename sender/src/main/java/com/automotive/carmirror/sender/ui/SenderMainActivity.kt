package com.automotive.carmirror.sender.ui

import android.Manifest
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.automotive.carmirror.sender.R
import com.automotive.carmirror.sender.ota.OtaUpdateInfo
import com.automotive.carmirror.sender.ota.OtaUpdateManager
import com.automotive.carmirror.sender.protocol.StreamProfile
import com.automotive.carmirror.sender.protocol.UdpDiscoveryManager
import com.automotive.carmirror.sender.service.ScreenCaptureService
import com.automotive.carmirror.sender.service.TouchAccessibilityService
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Interface Automotiva Profissional (Dark Glassmorphism Cockpit) do App Transmissor.
 * - Cartões translúcidos com bordas luminosas, indicador pulsante de transmissão e grade visual 2x2 de resolução/FPS.
 * - Checklist em tempo real de Touchback (Acessibilidade) e Áudio Interno.
 */
class SenderMainActivity : AppCompatActivity() {

    private lateinit var statusCard: LinearLayout
    private lateinit var statusPulseDot: View
    private lateinit var statusPillText: TextView
    private lateinit var statusHeadlineText: TextView
    private lateinit var statusSubText: TextView
    private lateinit var ipChipContainer: LinearLayout

    private lateinit var touchbackPill: TextView
    private lateinit var audioPermPill: TextView
    private lateinit var receiverOnlyAudioPill: TextView
    private lateinit var batteryPill: TextView
    private lateinit var dimScreenPill: TextView
    private lateinit var otaStatusPill: TextView
    private lateinit var otaBannerCard: LinearLayout
    private lateinit var otaBannerTitle: TextView
    private lateinit var otaBannerSub: TextView
    private lateinit var otaInstallBtn: Button
    private lateinit var connectToggleBtn: Button

    private lateinit var otaUpdateManager: OtaUpdateManager

    @Volatile
    private var latestOtaOffer: OtaUpdateInfo? = null
    private var isOtaDownloading = false

    private val profileCardViews = mutableListOf<LinearLayout>()
    private val profileTitleViews = mutableListOf<TextView>()
    private val profileSubViews = mutableListOf<TextView>()

    private var pulseAnimator: ObjectAnimator? = null
    private var selectedProfileIndex = 0

    private val projectionPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            val serviceIntent = Intent(this, ScreenCaptureService::class.java).apply {
                action = ScreenCaptureService.ACTION_START_CAPTURE
                putExtra(ScreenCaptureService.EXTRA_RESULT_CODE, result.resultCode)
                putExtra(ScreenCaptureService.EXTRA_RESULT_INTENT, result.data)
                putExtra(ScreenCaptureService.EXTRA_PROFILE_INDEX, selectedProfileIndex)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent)
            } else {
                startService(serviceIntent)
            }
        } else {
            Toast.makeText(this, "Permissão de espelhamento cancelada.", Toast.LENGTH_SHORT).show()
        }
    }

    private val runtimePermissionsLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { _ ->
        updatePermissionsChecklistUi()
        requestScreenCapturePermission()
    }

    private val audioOnlyPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { _ ->
        updatePermissionsChecklistUi()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        configureEdgeToEdgeDarkSystemBars()
        otaUpdateManager = OtaUpdateManager(this)
        setContentView(buildPremiumSenderUi())
        startPulseAnimation()
        observeServiceState()
        startOtaListener()
    }

    override fun onResume() {
        super.onResume()
        updatePermissionsChecklistUi()
        updateIpChips()
    }

    override fun onDestroy() {
        otaUpdateManager.stopListening()
        pulseAnimator?.cancel()
        pulseAnimator = null
        super.onDestroy()
    }

    @Suppress("DEPRECATION")
    private fun configureEdgeToEdgeDarkSystemBars() {
        window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)
        window.statusBarColor = Color.parseColor("#060A13")
        window.navigationBarColor = Color.parseColor("#060A13")
    }

    private fun buildPremiumSenderUi(): View {
        val rootFrame = FrameLayout(this).apply {
            background = GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(
                    Color.parseColor("#060A13"),
                    Color.parseColor("#0B1325"),
                    Color.parseColor("#070B14")
                )
            )
        }

        val scrollView = ScrollView(this).apply {
            isFillViewport = true
            isVerticalScrollBarEnabled = false
        }

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(28), dp(20), dp(110))
        }

        // ==================== HEADER HERO ====================
        val headerRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, dp(22))
        }

        val appIconView = ImageView(this).apply {
            setImageResource(R.drawable.ic_launcher_sender)
        }
        headerRow.addView(appIconView, LinearLayout.LayoutParams(dp(48), dp(48)).apply {
            marginEnd = dp(14)
        })

        val brandCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        brandCol.addView(TextView(this).apply {
            text = "CarMirror Pro"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 24f)
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            letterSpacing = -0.02f
        })
        brandCol.addView(TextView(this).apply {
            text = "Transmissor Automotivo • H.264 Low Latency"
            setTextColor(Color.parseColor("#94A3B8"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
        })
        headerRow.addView(brandCol, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))

        val versionPill = TextView(this).apply {
            text = "v2.0"
            setTextColor(Color.parseColor("#38BDF8"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            typeface = Typeface.DEFAULT_BOLD
            background = createBorderedRoundedDrawable("#0F2942", "#1E4976", 1, dp(12))
            setPadding(dp(10), dp(4), dp(10), dp(4))
        }
        headerRow.addView(versionPill)
        content.addView(headerRow)

        // ==================== BANNER DE ATUALIZAÇÃO OTA DO PC (AUTO-EXIBIDO) ====================
        otaBannerCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            setPadding(dp(16), dp(14), dp(16), dp(14))
            background = createBorderedRoundedDrawable("#0B253A", "#38BDF8", 2, dp(18))
        }
        otaBannerTitle = TextView(this).apply {
            text = "🚀 Nova Atualização Disponível no PC!"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15.5f)
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        }
        otaBannerCard.addView(otaBannerTitle)
        otaBannerSub = TextView(this).apply {
            text = "PC encontrado na rede Wi-Fi"
            setTextColor(Color.parseColor("#93C5FD"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
            setPadding(0, dp(3), 0, dp(10))
        }
        otaBannerCard.addView(otaBannerSub)
        otaInstallBtn = Button(this).apply {
            text = "⬇  BAIXAR E INSTALAR AGORA"
            isAllCaps = false
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            typeface = Typeface.DEFAULT_BOLD
            background = createGradientRippleDrawable("#0284C7", "#059669", dp(12))
            setPadding(dp(14), dp(10), dp(14), dp(10))
            setOnClickListener {
                latestOtaOffer?.let { startOtaDownload(it) }
            }
        }
        otaBannerCard.addView(otaInstallBtn, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ))
        content.addView(otaBannerCard, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = dp(14) })

        // ==================== CARD DE STATUS AO VIVO ====================
        statusCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(18), dp(18), dp(18))
            background = createBorderedRoundedDrawable("#111A2E", "#1E2D4A", 1, dp(18))
        }

        val statusTopRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        statusPulseDot = View(this).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#64748B"))
            }
        }
        statusTopRow.addView(statusPulseDot, LinearLayout.LayoutParams(dp(10), dp(10)).apply {
            marginEnd = dp(8)
        })

        statusPillText = TextView(this).apply {
            text = "PRONTO PARA TRANSMITIR"
            setTextColor(Color.parseColor("#94A3B8"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            typeface = Typeface.DEFAULT_BOLD
            letterSpacing = 0.08f
        }
        statusTopRow.addView(statusPillText)
        statusCard.addView(statusTopRow)

        statusHeadlineText = TextView(this).apply {
            text = "Desconectado"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            setPadding(0, dp(8), 0, dp(4))
        }
        statusCard.addView(statusHeadlineText)

        statusSubText = TextView(this).apply {
            text = "Ative o Roteador Wi-Fi (Hotspot) e inicie o espelhamento para a central T3L."
            setTextColor(Color.parseColor("#94A3B8"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setLineSpacing(dp(2).toFloat(), 1f)
        }
        statusCard.addView(statusSubText)

        // Chips de IPs Locais detectados
        ipChipContainer = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(12), 0, 0)
        }
        val ipScroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(ipChipContainer)
        }
        statusCard.addView(ipScroll)

        content.addView(statusCard, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = dp(16) })

        // ==================== CHECKLIST DE RECURSOS ====================
        content.addView(createSectionHeader("RECURSOS DO ESPELHAMENTO"))

        val checklistCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = createBorderedRoundedDrawable("#101829", "#1C2942", 1, dp(18))
            setPadding(dp(16), dp(8), dp(16), dp(8))
        }

        // Linha 1: Touchback
        touchbackPill = TextView(this)
        checklistCard.addView(
            createChecklistRow(
                iconText = "👆",
                title = "Controle Reverso (Touchback)",
                subtitle = "Controlar este celular pela tela da multimídia",
                badgeView = touchbackPill
            ) {
                openAccessibilityWithSamsungTip()
            }
        )

        // Divisor sutil
        checklistCard.addView(View(this).apply {
            setBackgroundColor(Color.parseColor("#1E293B"))
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(1)))

        // Linha 2: Áudio Interno
        audioPermPill = TextView(this)
        checklistCard.addView(
            createChecklistRow(
                iconText = "🔊",
                title = "Áudio Interno (44.1 kHz Stereo)",
                subtitle = "Transmitir Waze, Maps, YouTube e músicas ao carro",
                badgeView = audioPermPill
            ) {
                if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                    != PackageManager.PERMISSION_GRANTED
                ) {
                    audioOnlyPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                } else {
                    Toast.makeText(this, "Captura de áudio interno já autorizada!", Toast.LENGTH_SHORT).show()
                }
            }
        )

        checklistCard.addView(View(this).apply {
            setBackgroundColor(Color.parseColor("#1E293B"))
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(1)))

        // Linha 2.5: Som Somente na Multimídia T3L (Silencia alto-falante do celular + AGC)
        receiverOnlyAudioPill = TextView(this)
        checklistCard.addView(
            createChecklistRow(
                iconText = "🔇",
                title = "Som Só na Multimídia (T3L)",
                subtitle = "Silencia o celular ao conectar (volume 1 + AGC 100% na T3L)",
                badgeView = receiverOnlyAudioPill
            ) {
                if (ScreenCaptureService.serviceState.value.isStreaming) {
                    val intent = Intent(this, ScreenCaptureService::class.java).apply {
                        action = ScreenCaptureService.ACTION_TOGGLE_RECEIVER_ONLY_AUDIO
                    }
                    startService(intent)
                } else {
                    Toast.makeText(this, "Já vem ativado por padrão ao iniciar o espelhamento!", Toast.LENGTH_SHORT).show()
                }
            }
        )

        checklistCard.addView(View(this).apply {
            setBackgroundColor(Color.parseColor("#1E293B"))
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(1)))

        // Linha 3: Segundo Plano Sem Restrição de Bateria (Samsung OneUI)
        batteryPill = TextView(this)
        checklistCard.addView(
            createChecklistRow(
                iconText = "🔋",
                title = "Segundo Plano Contínuo",
                subtitle = "Impede o Android/Samsung de desligar o app",
                badgeView = batteryPill
            ) {
                requestBatteryOptimizationExemption()
            }
        )

        checklistCard.addView(View(this).apply {
            setBackgroundColor(Color.parseColor("#1E293B"))
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(1)))

        // Linha 4: Modo Tela Escura Real (0% Brilho físico no celular sem usar botão Power!)
        dimScreenPill = TextView(this)
        checklistCard.addView(
            createChecklistRow(
                iconText = "🌙",
                title = "Escurecer Tela do Celular (0%)",
                subtitle = "Usa brilho físico 0% sem apertar Power (T3L segue 100% acesa!)",
                badgeView = dimScreenPill
            ) {
                if (!Settings.System.canWrite(this)) {
                    requestWriteSettingsPermission()
                } else if (ScreenCaptureService.serviceState.value.isStreaming) {
                    val intent = Intent(this, ScreenCaptureService::class.java).apply {
                        action = ScreenCaptureService.ACTION_TOGGLE_DIM_SCREEN
                    }
                    startService(intent)
                } else {
                    Toast.makeText(
                        this,
                        "Liberado! Ao conectar na T3L a tela do celular escurecerá automaticamente para 0%.",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        )

        checklistCard.addView(View(this).apply {
            setBackgroundColor(Color.parseColor("#1E293B"))
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(1)))

        // Linha 5: Atualização Automática via GitHub Cloud + PC Wi-Fi
        otaStatusPill = TextView(this).apply {
            text = "VERIFICAR ›"
            setTextColor(Color.parseColor("#38BDF8"))
            background = createBorderedRoundedDrawable("#0F2942", "#0284C7", 1, dp(10))
        }
        checklistCard.addView(
            createChecklistRow(
                iconText = "☁️",
                title = "Atualização via GitHub / PC",
                subtitle = "Atualiza de qualquer lugar pela internet ou Wi-Fi local",
                badgeView = otaStatusPill
            ) {
                val offer = latestOtaOffer
                if (offer != null && offer.isNewer) {
                    startOtaDownload(offer)
                } else {
                    checkGitHubAndShowStatus()
                }
            }
        )

        content.addView(checklistCard, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = dp(18) })

        // ==================== SELETOR VISUAL 2x2 DE QUALIDADE ====================
        content.addView(createSectionHeader("QUALIDADE DE VÍDEO (PLACA T3L)"))

        val specs = listOf(
            Triple("1024 × 600", "30 FPS • 3.5 Mbps", "T3L Nativo (Estável)"),
            Triple("1024 × 600", "60 FPS • 5.0 Mbps", "T3L Fluido"),
            Triple("1280 × 720", "30 FPS • 4.5 Mbps", "HD Automotivo"),
            Triple("1280 × 720", "60 FPS • 6.0 Mbps", "HD Máximo")
        )

        val gridContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }

        for (rowIdx in 0..1) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
            }
            for (colIdx in 0..1) {
                val idx = rowIdx * 2 + colIdx
                val (resTitle, fpsMeta, tagLabel) = specs[idx]
                val card = buildProfileOptionCard(idx, resTitle, fpsMeta, tagLabel)
                row.addView(card, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                    if (colIdx == 0) marginEnd = dp(10)
                    bottomMargin = dp(10)
                })
            }
            gridContainer.addView(row)
        }
        content.addView(gridContainer)
        refreshProfileCardsSelection()

        scrollView.addView(content)
        rootFrame.addView(scrollView, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        ))

        // ==================== BOTÃO PRINCIPAL FLUTUANTE FIXO NO RODAPÉ ====================
        val bottomDock = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(14), dp(20), dp(22))
            background = GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(Color.parseColor("#00070B14"), Color.parseColor("#F2070B14"), Color.parseColor("#FF070B14"))
            )
        }

        connectToggleBtn = Button(this).apply {
            text = "▶  INICIAR ESPELHAMENTO"
            isAllCaps = false
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16.5f)
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            letterSpacing = 0.03f
            elevation = dp(8).toFloat()
            background = createGradientRippleDrawable("#059669", "#0284C7", dp(16))
            setPadding(dp(20), dp(16), dp(20), dp(16))
            setOnClickListener { onConnectButtonClicked() }
        }
        bottomDock.addView(connectToggleBtn, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ))

        rootFrame.addView(bottomDock, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.BOTTOM
        ))

        return rootFrame
    }

    private fun buildProfileOptionCard(
        index: Int,
        resTitle: String,
        fpsMeta: String,
        tagLabel: String
    ): LinearLayout {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(14), dp(14), dp(14))
            isClickable = true
            isFocusable = true
            setOnClickListener {
                selectedProfileIndex = index
                refreshProfileCardsSelection()
                if (ScreenCaptureService.serviceState.value.isStreaming) {
                    val changeIntent = Intent(this@SenderMainActivity, ScreenCaptureService::class.java).apply {
                        action = ScreenCaptureService.ACTION_CHANGE_PROFILE
                        putExtra(ScreenCaptureService.EXTRA_PROFILE_INDEX, index)
                    }
                    startService(changeIntent)
                }
            }
        }

        val titleTv = TextView(this).apply {
            text = resTitle
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        }
        val subTv = TextView(this).apply {
            text = fpsMeta
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
            setPadding(0, dp(2), 0, dp(6))
        }
        val tagTv = TextView(this).apply {
            text = tagLabel
            setTextColor(Color.parseColor("#94A3B8"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
        }

        card.addView(titleTv)
        card.addView(subTv)
        card.addView(tagTv)

        profileCardViews.add(card)
        profileTitleViews.add(titleTv)
        profileSubViews.add(subTv)
        return card
    }

    private fun refreshProfileCardsSelection() {
        for (i in profileCardViews.indices) {
            val selected = (i == selectedProfileIndex)
            if (selected) {
                profileCardViews[i].background = createBorderedRoundedDrawable("#0F2942", "#38BDF8", 2, dp(16))
                profileTitleViews[i].setTextColor(Color.WHITE)
                profileSubViews[i].setTextColor(Color.parseColor("#38BDF8"))
            } else {
                profileCardViews[i].background = createBorderedRoundedDrawable("#101829", "#1C2942", 1, dp(16))
                profileTitleViews[i].setTextColor(Color.parseColor("#CBD5E1"))
                profileSubViews[i].setTextColor(Color.parseColor("#64748B"))
            }
        }
    }

    private fun createChecklistRow(
        iconText: String,
        title: String,
        subtitle: String,
        badgeView: TextView,
        onClick: () -> Unit
    ): LinearLayout {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(12), 0, dp(12))
            isClickable = true
            isFocusable = true
            setOnClickListener { onClick() }
        }

        val iconCircle = TextView(this).apply {
            text = iconText
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            gravity = Gravity.CENTER
            background = createBorderedRoundedDrawable("#17233B", "#243554", 1, dp(12))
        }
        row.addView(iconCircle, LinearLayout.LayoutParams(dp(40), dp(40)).apply {
            marginEnd = dp(12)
        })

        val textCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        textCol.addView(TextView(this).apply {
            text = title
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14.5f)
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        })
        textCol.addView(TextView(this).apply {
            text = subtitle
            setTextColor(Color.parseColor("#64748B"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
            setPadding(0, dp(2), 0, 0)
        })
        row.addView(textCol, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
            marginEnd = dp(8)
        })

        badgeView.apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            typeface = Typeface.DEFAULT_BOLD
            setPadding(dp(10), dp(5), dp(10), dp(5))
        }
        row.addView(badgeView)

        return row
    }

    private fun startOtaListener() {
        // 1. Verifica automaticamente no GitHub Releases ao abrir o app (qualquer lugar via internet)
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
            otaBannerCard.visibility = View.VISIBLE
            otaBannerTitle.text = "🚀 Nova Atualização: v${offer.remoteVersionName}"
            otaBannerSub.text = "Fonte: ${offer.sourceLabel} • Instalada: v${otaUpdateManager.getCurrentVersionName()}"
            otaStatusPill.text = "ATUALIZAR!"
            otaStatusPill.setTextColor(Color.parseColor("#34D399"))
            otaStatusPill.background = createBorderedRoundedDrawable("#0B2A22", "#10B981", 1, dp(10))
        } else {
            otaStatusPill.text = "v${offer.remoteVersionName} OK ✓"
            otaStatusPill.setTextColor(Color.parseColor("#34D399"))
            otaStatusPill.background = createBorderedRoundedDrawable("#0B2A22", "#10B981", 1, dp(10))
        }
    }

    private fun checkGitHubAndShowStatus() {
        otaStatusPill.text = "BUSCANDO..."
        lifecycleScope.launch {
            val offer = otaUpdateManager.checkGitHubRelease()
            if (offer != null) {
                applyDetectedOtaOffer(offer)
                if (offer.isNewer) {
                    startOtaDownload(offer)
                } else {
                    AlertDialog.Builder(this@SenderMainActivity)
                        .setTitle("CarMirror Atualizado")
                        .setMessage("Você já está na versão mais recente no GitHub (v${offer.remoteVersionName}).")
                        .setPositiveButton("OK", null)
                        .setNeutralButton("Usar PC Local (IP)") { _, _ -> showManualOtaIpDialog() }
                        .show()
                }
            } else {
                otaStatusPill.text = "VERIFICAR ›"
                showManualOtaIpDialog()
            }
        }
    }

    private fun startOtaDownload(offer: OtaUpdateInfo) {
        if (isOtaDownloading) return
        isOtaDownloading = true
        otaBannerCard.visibility = View.VISIBLE
        otaInstallBtn.isEnabled = false
        otaInstallBtn.text = "⬇  BAIXANDO... 0%"

        lifecycleScope.launch {
            otaUpdateManager.downloadAndInstall(
                info = offer,
                onProgress = { pct ->
                    otaInstallBtn.text = if (pct >= 100) "✓  ABRINDO INSTALADOR..." else "⬇  BAIXANDO... $pct%"
                },
                onNeedInstallPermission = {
                    isOtaDownloading = false
                    otaInstallBtn.isEnabled = true
                    otaInstallBtn.text = "⬇  BAIXAR E INSTALAR AGORA"
                    Toast.makeText(
                        this@SenderMainActivity,
                        "Ative 'Permitir desta fonte' e toque em Atualizar novamente.",
                        Toast.LENGTH_LONG
                    ).show()
                },
                onError = { err ->
                    isOtaDownloading = false
                    otaInstallBtn.isEnabled = true
                    otaInstallBtn.text = "⬇  TENTAR NOVAMENTE"
                    Toast.makeText(this@SenderMainActivity, err, Toast.LENGTH_LONG).show()
                }
            )
            isOtaDownloading = false
            otaInstallBtn.isEnabled = true
            otaInstallBtn.text = "⬇  INSTALAR v${offer.remoteVersionName}"
        }
    }

    private fun showManualOtaIpDialog() {
        val ipInput = EditText(this).apply {
            hint = "IP do PC (ex: 192.168.1.100)"
            setSingleLine(true)
        }
        AlertDialog.Builder(this)
            .setTitle("Atualizar via PC Local (Wi-Fi)")
            .setMessage("Sem acesso ao GitHub agora ou deseja atualizar direto pelo PC local? Digite o IP exibido no ENVIAR_ATUALIZACAO_WIFI.bat:")
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
                            Toast.makeText(this@SenderMainActivity, "Servidor OTA não encontrado em $ip:8765", Toast.LENGTH_LONG).show()
                        }
                    }
                }
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun openAccessibilityWithSamsungTip() {
        if (TouchAccessibilityService.isServiceEnabled()) {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            return
        }
        AlertDialog.Builder(this)
            .setTitle("Ativar Controle Reverso (Touchback)")
            .setMessage(
                "1. Na próxima tela, abra 'Aplicativos instalados' e ative 'CarMirror Controle Automotivo'.\n\n" +
                    "2. Se o Samsung exibir 'Configuração restrita': volte, vá em Configurações > Aplicativos > CarMirror Sender > menu ⋮ (topo direito) > 'Permitir configurações restritas' e ative novamente."
            )
            .setPositiveButton("Abrir Acessibilidade") { _, _ ->
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun onConnectButtonClicked() {
        val state = ScreenCaptureService.serviceState.value
        if (state.isStreaming) {
            val stopIntent = Intent(this, ScreenCaptureService::class.java).apply {
                action = ScreenCaptureService.ACTION_STOP_CAPTURE
            }
            startService(stopIntent)
        } else {
            val neededPermissions = mutableListOf<String>()
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED
            ) {
                neededPermissions.add(Manifest.permission.RECORD_AUDIO)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                neededPermissions.add(Manifest.permission.POST_NOTIFICATIONS)
            }

            if (neededPermissions.isNotEmpty()) {
                runtimePermissionsLauncher.launch(neededPermissions.toTypedArray())
            } else {
                requestScreenCapturePermission()
            }
        }
    }

    private fun requestScreenCapturePermission() {
        val projectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        projectionPermissionLauncher.launch(projectionManager.createScreenCaptureIntent())
    }

    private fun observeServiceState() {
        lifecycleScope.launch {
            ScreenCaptureService.serviceState.collectLatest { state ->
                val activeIdx = StreamProfile.ALL_PROFILES.indexOf(state.activeProfile)
                if (activeIdx >= 0 && activeIdx != selectedProfileIndex) {
                    selectedProfileIndex = activeIdx
                    refreshProfileCardsSelection()
                }

                when {
                    !state.isStreaming -> {
                        statusCard.background = createBorderedRoundedDrawable("#111A2E", "#1E2D4A", 1, dp(18))
                        setDotColor("#64748B")
                        statusPillText.text = "MODO STANDBY"
                        statusPillText.setTextColor(Color.parseColor("#94A3B8"))
                        statusHeadlineText.text = "Pronto para Espelhar"
                        statusSubText.text = "Ligue o Roteador Wi-Fi (Hotspot) e toque abaixo para transmitir tela e áudio para a multimídia T3L."
                        connectToggleBtn.text = "▶  INICIAR ESPELHAMENTO"
                        connectToggleBtn.background = createGradientRippleDrawable("#059669", "#0284C7", dp(16))
                    }

                    state.isReceiverConnected -> {
                        statusCard.background = createBorderedRoundedDrawable("#0B241E", "#10B981", 2, dp(18))
                        setDotColor("#10B981")
                        statusPillText.text = "AO VIVO • OKIO ABR"
                        statusPillText.setTextColor(Color.parseColor("#34D399"))
                        statusHeadlineText.text = "Conectado à Multimídia T3L"
                        val mbps = String.format("%.1f", (state.activeBitrateKbps.takeIf { it > 0 } ?: (state.activeProfile.bitrateBps / 1000)) / 1000.0)
                        val rttTxt = if (state.rttMs > 0) "${state.rttMs}ms" else "<10ms"
                        val fpsTxt = if (state.liveFps > 0) "${state.liveFps}fps" else "${state.activeProfile.fps}fps"
                        statusSubText.text = "⚡ $rttTxt  •  ${mbps} Mbps  •  ${state.activeProfile.width}×${state.activeProfile.height} @ $fpsTxt (${state.connectedReceiverIp})"
                        connectToggleBtn.text = "■  ENCERRAR TRANSMISSÃO"
                        connectToggleBtn.background = createGradientRippleDrawable("#DC2626", "#991B1B", dp(16))
                    }

                    else -> {
                        statusCard.background = createBorderedRoundedDrawable("#221C0D", "#D97706", 1, dp(18))
                        setDotColor("#F59E0B")
                        statusPillText.text = "AUTO-DISCOVERY ATIVO"
                        statusPillText.setTextColor(Color.parseColor("#FBBF24"))
                        statusHeadlineText.text = "Aguardando Central T3L..."
                        statusSubText.text = "Abra o app CarMirror na central multimídia do carro e toque em CONECTAR."
                        connectToggleBtn.text = "■  PARAR TRANSMISSÃO"
                        connectToggleBtn.background = createGradientRippleDrawable("#DC2626", "#991B1B", dp(16))
                    }
                }
                updateIpChips()
                updateDimScreenPillUi(state.isDimScreenActive)
                updateReceiverOnlyAudioPillUi(state.isReceiverOnlyAudio)
            }
        }
    }

    private fun updateReceiverOnlyAudioPillUi(receiverOnly: Boolean) {
        if (receiverOnly) {
            receiverOnlyAudioPill.text = "SÓ NA T3L ✓"
            receiverOnlyAudioPill.setTextColor(Color.parseColor("#34D399"))
            receiverOnlyAudioPill.background = createBorderedRoundedDrawable("#0B2A22", "#10B981", 1, dp(10))
        } else {
            receiverOnlyAudioPill.text = "AMBOS"
            receiverOnlyAudioPill.setTextColor(Color.parseColor("#94A3B8"))
            receiverOnlyAudioPill.background = createBorderedRoundedDrawable("#162033", "#243554", 1, dp(10))
        }
    }

    private fun requestBatteryOptimizationExemption() {
        try {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            if (!pm.isIgnoringBatteryOptimizations(packageName)) {
                val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                    data = Uri.parse("package:$packageName")
                }
                startActivity(intent)
            } else {
                Toast.makeText(this, "Modo Segundo Plano Contínuo já está liberado!", Toast.LENGTH_SHORT).show()
            }
        } catch (_: Exception) {
            startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        }
    }

    private fun requestWriteSettingsPermission() {
        AlertDialog.Builder(this)
            .setTitle("Escurecer Tela do Celular (0%) sem desligar a T3L")
            .setMessage(
                "No Android 14/15, apertar o botão físico Power bloqueia o celular e o sistema encerra o espelhamento.\n\n" +
                    "Liberando 'Modificar configurações do sistema', o CarMirror reduz o brilho físico do seu celular para 0% automaticamente ao conectar na multimídia — mantendo a T3L 100% acesa!"
            )
            .setPositiveButton("Liberar Agora") { _, _ ->
                try {
                    val intent = Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS).apply {
                        data = Uri.parse("package:$packageName")
                    }
                    startActivity(intent)
                } catch (_: Exception) {
                    startActivity(Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS))
                }
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun updateDimScreenPillUi(dimActive: Boolean) {
        val canWrite = Settings.System.canWrite(this)
        when {
            !canWrite -> {
                dimScreenPill.text = "LIBERAR ›"
                dimScreenPill.setTextColor(Color.parseColor("#FBBF24"))
                dimScreenPill.background = createBorderedRoundedDrawable("#2A1E0A", "#D97706", 1, dp(10))
            }
            dimActive -> {
                dimScreenPill.text = "ATIVA (0%) ✓"
                dimScreenPill.setTextColor(Color.parseColor("#38BDF8"))
                dimScreenPill.background = createBorderedRoundedDrawable("#0F2942", "#38BDF8", 1, dp(10))
            }
            ScreenCaptureService.serviceState.value.isStreaming -> {
                dimScreenPill.text = "ATIVAR 0%"
                dimScreenPill.setTextColor(Color.parseColor("#94A3B8"))
                dimScreenPill.background = createBorderedRoundedDrawable("#162033", "#243554", 1, dp(10))
            }
            else -> {
                dimScreenPill.text = "AUTO 0% ✓"
                dimScreenPill.setTextColor(Color.parseColor("#34D399"))
                dimScreenPill.background = createBorderedRoundedDrawable("#0B2A22", "#10B981", 1, dp(10))
            }
        }
    }

    private fun updatePermissionsChecklistUi() {
        val touchEnabled = TouchAccessibilityService.isServiceEnabled()
        if (touchEnabled) {
            touchbackPill.text = "ATIVO ✓"
            touchbackPill.setTextColor(Color.parseColor("#34D399"))
            touchbackPill.background = createBorderedRoundedDrawable("#0B2A22", "#10B981", 1, dp(10))
        } else {
            touchbackPill.text = "ATIVAR ›"
            touchbackPill.setTextColor(Color.parseColor("#FBBF24"))
            touchbackPill.background = createBorderedRoundedDrawable("#2A1E0A", "#D97706", 1, dp(10))
        }

        val audioGranted = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        if (audioGranted) {
            audioPermPill.text = "OK ✓"
            audioPermPill.setTextColor(Color.parseColor("#34D399"))
            audioPermPill.background = createBorderedRoundedDrawable("#0B2A22", "#10B981", 1, dp(10))
        } else {
            audioPermPill.text = "PERMITIR ›"
            audioPermPill.setTextColor(Color.parseColor("#38BDF8"))
            audioPermPill.background = createBorderedRoundedDrawable("#0F2942", "#0284C7", 1, dp(10))
        }

        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        val batteryExempt = pm.isIgnoringBatteryOptimizations(packageName)
        if (batteryExempt) {
            batteryPill.text = "LIBERADO ✓"
            batteryPill.setTextColor(Color.parseColor("#34D399"))
            batteryPill.background = createBorderedRoundedDrawable("#0B2A22", "#10B981", 1, dp(10))
        } else {
            batteryPill.text = "LIBERAR ›"
            batteryPill.setTextColor(Color.parseColor("#FBBF24"))
            batteryPill.background = createBorderedRoundedDrawable("#2A1E0A", "#D97706", 1, dp(10))
        }

        updateDimScreenPillUi(ScreenCaptureService.serviceState.value.isDimScreenActive)
        updateReceiverOnlyAudioPillUi(ScreenCaptureService.serviceState.value.isReceiverOnlyAudio)
    }

    private fun updateIpChips() {
        ipChipContainer.removeAllViews()
        val ips = UdpDiscoveryManager.getLocalIpAddresses()
        if (ips.isEmpty()) {
            ipChipContainer.addView(createMetaChip("Sem Wi-Fi / Hotspot ativo", "#64748B"))
            return
        }
        ips.forEach { ip ->
            ipChipContainer.addView(createMetaChip("IP $ip", "#38BDF8"))
        }
        ipChipContainer.addView(createMetaChip("TCP :49152", "#94A3B8"))
    }

    private fun createMetaChip(label: String, textHex: String): TextView {
        return TextView(this).apply {
            text = label
            setTextColor(Color.parseColor(textHex))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            typeface = Typeface.MONOSPACE
            background = createBorderedRoundedDrawable("#0A101D", "#1E293B", 1, dp(8))
            setPadding(dp(8), dp(4), dp(8), dp(4))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { marginEnd = dp(6) }
        }
    }

    private fun createSectionHeader(title: String): TextView {
        return TextView(this).apply {
            text = title
            setTextColor(Color.parseColor("#64748B"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            typeface = Typeface.DEFAULT_BOLD
            letterSpacing = 0.08f
            setPadding(dp(4), 0, 0, dp(8))
        }
    }

    private fun setDotColor(hex: String) {
        (statusPulseDot.background as? GradientDrawable)?.setColor(Color.parseColor(hex))
    }

    private fun startPulseAnimation() {
        pulseAnimator = ObjectAnimator.ofFloat(statusPulseDot, View.ALPHA, 1f, 0.25f).apply {
            duration = 950L
            repeatMode = ValueAnimator.REVERSE
            repeatCount = ValueAnimator.INFINITE
            start()
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
