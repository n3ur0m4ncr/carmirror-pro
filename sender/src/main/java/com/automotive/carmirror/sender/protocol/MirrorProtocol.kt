package com.automotive.carmirror.sender.protocol

/**
 * Especificação binária do protocolo CarMirror de Baixa Latência.
 * Utiliza conexão TCP Full-Duplex com TCP_NODELAY (Nagle desativado) e IPTOS_LOWDELAY.
 */
object MirrorProtocol {
    const val UDP_DISCOVERY_PORT = 49150
    const val TCP_STREAM_PORT = 49152

    const val DISCOVERY_MAGIC_BEACON = "CARMIRROR_SENDER_V1"
    const val DISCOVERY_MAGIC_PROBE = "CARMIRROR_RECEIVER_PROBE_V1"

    // Downstream (Celular -> Central Multimídia T3L)
    const val TYPE_VIDEO_CONFIG: Byte = 0x01   // SPS / PPS (CSD-0)
    const val TYPE_VIDEO_KEYFRAME: Byte = 0x02 // IDR Frame (I-Frame)
    const val TYPE_VIDEO_FRAME: Byte = 0x03    // P-Frame
    const val TYPE_AUDIO_PCM: Byte = 0x04      // PCM 16-bit 44.1kHz Stereo
    const val TYPE_STREAM_META: Byte = 0x05    // Resolução (W, H, FPS)
    const val TYPE_HEARTBEAT: Byte = 0x06      // Keep-alive
    const val TYPE_TOUCHBACK_STATUS: Byte = 0x07 // 1 = Touchback ativo, 0 = Pendente

    // Upstream (Central Multimídia T3L -> Celular)
    const val CMD_TOUCH_EVENT: Byte = 0x10     // Evento de toque normalizado (0.0..1.0)
    const val CMD_REQUEST_IDR: Byte = 0x11     // Solicitação imediata de Keyframe (I-Frame)
    const val CMD_CHANGE_PROFILE: Byte = 0x12  // Ajuste dinâmico de resolução/FPS
    const val CMD_SYSTEM_NAV: Byte = 0x13      // Botões virtuais: Back(1), Home(2), Recents(3)
    const val CMD_MEDIA_CONTROL: Byte = 0x14   // 1=PlayPause, 2=Next, 3=Prev, 4=Vol+, 5=Vol-, 6=ToggleAudio
    const val CMD_LAUNCH_APP: Byte = 0x15      // 1=Waze, 2=Maps, 3=Spotify, 4=YouTube, 5=YTMusic
    const val CMD_STOP_REMOTE: Byte = 0x16     // Encerra transmissão no celular remotamente

    // Ações de Toque
    const val TOUCH_ACTION_DOWN: Byte = 0x00
    const val TOUCH_ACTION_UP: Byte = 0x01
    const val TOUCH_ACTION_MOVE: Byte = 0x02
    const val TOUCH_ACTION_CANCEL: Byte = 0x03

    // Configurações de Áudio de Baixa Latência
    const val AUDIO_SAMPLE_RATE = 44100
    const val AUDIO_CHANNELS = 2 // Stereo
}

data class StreamProfile(
    val label: String,
    val width: Int,
    val height: Int,
    val fps: Int,
    val bitrateBps: Int,
    val dpi: Int = 240
) {
    companion object {
        val PROFILE_T3L_NATIVE = StreamProfile(
            label = "T3L Nativo (1024x600 @ 30fps - 3.5 Mbps)",
            width = 1024,
            height = 600,
            fps = 30,
            bitrateBps = 3_500_000
        )
        val PROFILE_T3L_SMOOTH = StreamProfile(
            label = "T3L Fluido (1024x600 @ 60fps - 5.0 Mbps)",
            width = 1024,
            height = 600,
            fps = 60,
            bitrateBps = 5_000_000
        )
        val PROFILE_HD_720P = StreamProfile(
            label = "HD Automotivo (1280x720 @ 30fps - 4.5 Mbps)",
            width = 1280,
            height = 720,
            fps = 30,
            bitrateBps = 4_500_000
        )
        val PROFILE_HD_720P_60 = StreamProfile(
            label = "HD 60FPS (1280x720 @ 60fps - 6.0 Mbps)",
            width = 1280,
            height = 720,
            fps = 60,
            bitrateBps = 6_000_000
        )

        val ALL_PROFILES = listOf(
            PROFILE_T3L_NATIVE,
            PROFILE_T3L_SMOOTH,
            PROFILE_HD_720P,
            PROFILE_HD_720P_60
        )
    }
}
