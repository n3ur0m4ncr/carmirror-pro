package com.automotive.carmirror.sender.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Color
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.PointF
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.DisplayMetrics
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import com.automotive.carmirror.sender.protocol.MirrorProtocol
import kotlin.math.hypot

/**
 * AccessibilityService responsável pelo Touchback (Controle Reverso por Toque).
 * Converte coordenadas normalizadas (0.0..1.0) vindas da central multimídia Allwinner T3L
 * para a geometria real da tela do smartphone (compensando automaticamente letterbox/pillarbox
 * entre o aspect ratio 19.5:9 do celular e 16:9 / 1024x600 da multimídia, tanto em retrato quanto paisagem).
 */
class TouchAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "TouchAccessibilitySvc"

        @Volatile
        var instance: TouchAccessibilityService? = null
            private set

        @Volatile
        var onAvailabilityChanged: ((Boolean) -> Unit)? = null

        fun isServiceEnabled(): Boolean = instance != null
    }

    private val touchWaypoints = ArrayList<PointF>(64)
    private var touchDownTimestampMs: Long = 0L
    private val mainHandler = Handler(Looper.getMainLooper())
    private var keepAwakeOverlayView: View? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        onAvailabilityChanged?.invoke(true)
        Log.i(TAG, "TouchAccessibilityService conectado e pronto para Touchback")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Não precisamos inspecionar árvore de views; apenas injetar gestos de toque
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        removeKeepAwakeOverlay()
        if (instance === this) {
            instance = null
            onAvailabilityChanged?.invoke(false)
        }
        super.onDestroy()
    }

    /**
     * Mantém o display ativo em segundo plano em QUALQUER aplicativo (Waze, Maps, YouTube, Spotify)
     * usando uma janela MATCH_PARENT totalmente transparente (Color.TRANSPARENT) TYPE_ACCESSIBILITY_OVERLAY
     * com FLAG_KEEP_SCREEN_ON e FLAG_NOT_TOUCHABLE.
     * Por ser MATCH_PARENT, a OneUI da Samsung aplica corretamente o screenBrightness = 0.001f (mínimo físico),
     * enquanto o MediaProjection continua enviando a imagem com 100% de brilho e nitidez para a T3L!
     */
    fun updateKeepAwakeOverlay(keepAwake: Boolean, dimPhoneScreen: Boolean) {
        mainHandler.post {
            if (!keepAwake) {
                removeKeepAwakeOverlay()
                return@post
            }
            try {
                val wm = getSystemService(WINDOW_SERVICE) as WindowManager
                val params = WindowManager.LayoutParams(
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                        WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                        WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                    PixelFormat.TRANSLUCENT
                ).apply {
                    gravity = Gravity.TOP or Gravity.START
                    x = 0
                    y = 0
                    screenBrightness = if (dimPhoneScreen) 0.001f else WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
                }

                val existing = keepAwakeOverlayView
                if (existing == null) {
                    val v = View(this).apply {
                        setBackgroundColor(Color.TRANSPARENT)
                    }
                    wm.addView(v, params)
                    keepAwakeOverlayView = v
                } else {
                    wm.updateViewLayout(existing, params)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Aviso ao atualizar overlay Keep-Awake: ${e.message}")
            }
        }
    }

    private fun removeKeepAwakeOverlay() {
        mainHandler.post {
            val existing = keepAwakeOverlayView ?: return@post
            try {
                val wm = getSystemService(WINDOW_SERVICE) as WindowManager
                wm.removeViewImmediate(existing)
            } catch (_: Exception) {}
            keepAwakeOverlayView = null
        }
    }

    /**
     * Recebe um evento de toque da central multimídia T3L e reconstrói cliques, long-press,
     * swipes e pinças/arrastos de mapa em tempo real.
     */
    @Synchronized
    fun handleRemoteTouch(
        action: Byte,
        normX: Float,
        normY: Float,
        streamWidth: Int,
        streamHeight: Int
    ) {
        val mappedPoint = mapNormalizedStreamToPhysicalScreen(normX, normY, streamWidth, streamHeight)

        when (action) {
            MirrorProtocol.TOUCH_ACTION_DOWN -> {
                touchWaypoints.clear()
                touchWaypoints.add(mappedPoint)
                touchDownTimestampMs = System.currentTimeMillis()
            }

            MirrorProtocol.TOUCH_ACTION_MOVE -> {
                if (touchWaypoints.isNotEmpty()) {
                    val last = touchWaypoints.last()
                    val dist = hypot((mappedPoint.x - last.x).toDouble(), (mappedPoint.y - last.y).toDouble())
                    // Filtra micro-ruídos do touch capacitivo da T3L (< 4 pixels)
                    if (dist >= 4.0 && touchWaypoints.size < 60) {
                        touchWaypoints.add(mappedPoint)
                    }
                } else {
                    touchWaypoints.add(mappedPoint)
                    touchDownTimestampMs = System.currentTimeMillis()
                }
            }

            MirrorProtocol.TOUCH_ACTION_UP -> {
                if (touchWaypoints.isEmpty()) {
                    touchWaypoints.add(mappedPoint)
                } else {
                    touchWaypoints.add(mappedPoint)
                }
                val elapsedMs = (System.currentTimeMillis() - touchDownTimestampMs).coerceAtLeast(15L)
                dispatchReconstructedGesture(ArrayList(touchWaypoints), elapsedMs)
                touchWaypoints.clear()
            }

            MirrorProtocol.TOUCH_ACTION_CANCEL -> {
                touchWaypoints.clear()
            }
        }
    }

    /**
     * Executa ações globais enviadas pela barra lateral da central multimídia (Voltar, Home, Recentes).
     */
    fun performSystemNavigation(navCode: Int) {
        when (navCode) {
            1 -> performGlobalAction(GLOBAL_ACTION_BACK)
            2 -> performGlobalAction(GLOBAL_ACTION_HOME)
            3 -> performGlobalAction(GLOBAL_ACTION_RECENTS)
        }
    }

    /**
     * Abre aplicativos no smartphone diretamente a partir de um clique no painel da multimídia T3L.
     * Como AccessibilityService, possui isenção de Background Activity Launch (BAL) no Android 10-15.
     */
    fun launchAppByPackage(packageName: String): Boolean {
        return try {
            val intent = packageManager.getLaunchIntentForPackage(packageName) ?: return false
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
            startActivity(intent)
            true
        } catch (e: Exception) {
            Log.w(TAG, "Erro ao abrir app $packageName: ${e.message}")
            false
        }
    }

    private fun dispatchReconstructedGesture(points: List<PointF>, elapsedMs: Long) {
        if (points.isEmpty()) return

        val start = points.first()
        val end = points.last()
        val totalDistance = hypot((end.x - start.x).toDouble(), (end.y - start.y).toDouble())

        val path = Path()
        val durationMs: Long

        if (totalDistance < 18.0) {
            // Toque simples (Tap) ou Toque Longo (Long Press)
            path.moveTo(start.x, start.y)
            durationMs = if (elapsedMs >= 500L) {
                650L // Long press
            } else {
                35L  // Tap instantâneo
            }
        } else {
            // Arrasto / Swipe / Navegação no Waze ou Google Maps
            path.moveTo(start.x, start.y)
            for (i in 1 until points.size) {
                val pt = points[i]
                path.lineTo(pt.x, pt.y)
            }
            durationMs = elapsedMs.coerceIn(70L, 700L)
        }

        try {
            val stroke = GestureDescription.StrokeDescription(path, 0L, durationMs)
            val gesture = GestureDescription.Builder()
                .addStroke(stroke)
                .build()
            dispatchGesture(gesture, null, null)
        } catch (e: Exception) {
            Log.w(TAG, "Erro ao despachar gesto via AccessibilityService: ${e.message}")
        }
    }

    /**
     * Mapeia coordenadas normalizadas (0..1) do frame H.264 (ex: 1024x600 ou 1280x720)
     * para as coordenadas físicas reais do display do smartphone, descontando barras
     * de letterbox/pillarbox do VirtualDisplay em modo retrato ou paisagem.
     */
    private fun mapNormalizedStreamToPhysicalScreen(
        normX: Float,
        normY: Float,
        streamWidth: Int,
        streamHeight: Int
    ): PointF {
        val (physW, physH) = getPhysicalScreenSize()
        val sW = streamWidth.coerceAtLeast(1).toFloat()
        val sH = streamHeight.coerceAtLeast(1).toFloat()

        // VirtualDisplay com FLAG_AUTO_MIRROR centraliza a tela física dentro do frame sW x sH
        val scale = minOf(sW / physW.toFloat(), sH / physH.toFloat())
        val renderedW = physW * scale
        val renderedH = physH * scale
        val offsetX = (sW - renderedW) / 2f
        val offsetY = (sH - renderedH) / 2f

        val streamX = normX.coerceIn(0f, 1f) * sW
        val streamY = normY.coerceIn(0f, 1f) * sH

        val physX = ((streamX - offsetX) / scale).coerceIn(1f, (physW - 2).toFloat())
        val physY = ((streamY - offsetY) / scale).coerceIn(1f, (physH - 2).toFloat())

        return PointF(physX, physY)
    }

    @Suppress("DEPRECATION")
    private fun getPhysicalScreenSize(): Pair<Int, Int> {
        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds = wm.maximumWindowMetrics.bounds
            Pair(bounds.width(), bounds.height())
        } else {
            val metrics = DisplayMetrics()
            wm.defaultDisplay.getRealMetrics(metrics)
            Pair(metrics.widthPixels, metrics.heightPixels)
        }
    }
}
