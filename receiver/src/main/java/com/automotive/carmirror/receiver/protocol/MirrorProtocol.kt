package com.automotive.carmirror.receiver.protocol

/**
 * Constantes binárias do protocolo CarMirror compatíveis com o App Transmissor.
 */
object MirrorProtocol {
    const val UDP_DISCOVERY_PORT = 49150
    const val TCP_STREAM_PORT = 49152

    const val DISCOVERY_MAGIC_BEACON = "CARMIRROR_SENDER_V1"
    const val DISCOVERY_MAGIC_PROBE = "CARMIRROR_RECEIVER_PROBE_V1"
    const val NSD_SERVICE_TYPE = "_carmirror._tcp."

    // Downstream (Celular -> Central Multimídia T3L)
    const val TYPE_VIDEO_CONFIG: Byte = 0x01
    const val TYPE_VIDEO_KEYFRAME: Byte = 0x02
    const val TYPE_VIDEO_FRAME: Byte = 0x03
    const val TYPE_AUDIO_PCM: Byte = 0x04
    const val TYPE_STREAM_META: Byte = 0x05
    const val TYPE_HEARTBEAT: Byte = 0x06
    const val TYPE_TOUCHBACK_STATUS: Byte = 0x07
    const val TYPE_PING_TELEMETRY: Byte = 0x08

    // Upstream (Central Multimídia T3L -> Celular)
    const val CMD_TOUCH_EVENT: Byte = 0x10
    const val CMD_REQUEST_IDR: Byte = 0x11
    const val CMD_CHANGE_PROFILE: Byte = 0x12
    const val CMD_SYSTEM_NAV: Byte = 0x13
    const val CMD_MEDIA_CONTROL: Byte = 0x14
    const val CMD_LAUNCH_APP: Byte = 0x15
    const val CMD_STOP_REMOTE: Byte = 0x16
    const val CMD_PONG: Byte = 0x17

    // Ações de Toque
    const val TOUCH_ACTION_DOWN: Byte = 0x00
    const val TOUCH_ACTION_UP: Byte = 0x01
    const val TOUCH_ACTION_MOVE: Byte = 0x02
    const val TOUCH_ACTION_CANCEL: Byte = 0x03

    // Áudio PCM Baixa Latência
    const val AUDIO_SAMPLE_RATE = 44100
}

data class DiscoveredSender(
    val ipAddress: String,
    val tcpPort: Int,
    val width: Int,
    val height: Int,
    val fps: Int,
    val deviceName: String
)
