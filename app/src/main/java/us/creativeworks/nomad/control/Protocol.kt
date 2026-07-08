package us.creativeworks.nomad.control

/**
 * Nomad ND1 ("Mako") wire protocol.
 *
 * Every value here was recovered from the original app's decompiled source.
 * See NOMAD-ND1-PROTOCOL.md at the repo root for the full annotated spec.
 *
 * All traffic is UDP to [CAR_IP]:[PORT]. The client socket must be bound to
 * LOCAL port [PORT] as well (the car replies to 8234).
 */
object Protocol {
    const val CAR_IP = "192.168.0.1"
    const val PORT = 8234

    /** SSID substring that identifies the car's Wi-Fi AP. */
    const val SSID_PREFIX = "NOMAD_ND1-"

    /** RTSP video stream. */
    const val RTSP_URL = "rtsp://192.168.0.1/vs1"

    // --- Timing (from the app) ------------------------------------------------
    const val CONNECT_BURST = 10          // MAKO_CONNECT sent this many times
    const val CONNECT_BURST_INTERVAL_MS = 50L
    const val DRIVE_INTERVAL_MS = 100L     // 10 Hz drive stream while engaged
    const val BATTERY_POLL_MS = 6_000L
    const val KEEPALIVE_TIMEOUT_MS = 60_000L
    const val CONNECT_WAIT_MS = 4_000L     // wait for first reply before "failed"

    // --- Steering domain ------------------------------------------------------
    const val STEER_CENTER_DEFAULT = 0x80  // 128
    const val STEER_TOTAL_STEP = 164        // range = center +/- 82
    const val SPEED_TOTAL_STEP = 230        // max PWM magnitude

    // --- ASCII string commands ------------------------------------------------
    const val MAKO_CONNECT = "MAKO_CONNECT"
    const val MAKO_DISCONNECT = "MAKO_DISCONNECT"
    const val MAKO_READ_BATT = "MAKO_READ_BATT"
    const val MAKO_VERSION = "MAKO_VERSION"
    const val MAKO_MACADD = "MAKO_MACADD"
    const val MAKO_LED1_ON = "MAKO_LED1_ON"    // upper lights
    const val MAKO_LED1_OFF = "MAKO_LED1_OFF"
    const val MAKO_LED2_ON = "MAKO_LED2_ON"    // lower lights
    const val MAKO_LED2_OFF = "MAKO_LED2_OFF"

    // --- Binary command header ------------------------------------------------
    /** Fixed 8-byte prefix common to drive/config packets; byte[7]=0x21 opcode for drive. */
    private val DRIVE_HEADER = byteArrayOf(
        0xC0.toByte(), 0xA8.toByte(), 0x01, 0x01, 0x00, 0x00, 0x04, 0x21
    )

    /**
     * Drive command: forwardPwm, backwardPwm, steeringLevel (each 0..255).
     * Only one of fw/bw should be non-zero for normal driving.
     */
    fun drive(forwardPwm: Int, backwardPwm: Int, steering: Int): ByteArray =
        DRIVE_HEADER + byteArrayOf(
            (forwardPwm and 0xFF).toByte(),
            (backwardPwm and 0xFF).toByte(),
            (steering and 0xFF).toByte(),
        )

    /** Neutral/center packet (motors 0, current steering). */
    fun center(steering: Int): ByteArray = drive(0, 0, steering)

    /** Hard brake pulse used when reversing direction abruptly (fw=bw=0xFF). */
    fun brakePulse(steering: Int): ByteArray = drive(0xFF, 0xFF, steering)

    // --- Config / query commands (settings screen) ---------------------------
    private fun hex(s: String): ByteArray {
        val clean = s.replace(" ", "")
        return ByteArray(clean.length / 2) {
            clean.substring(it * 2, it * 2 + 2).toInt(16).toByte()
        }
    }

    fun setCenterPreset(value: Int) = hex("C0A80101000004414040") + value.and(0xFF).toByte()
    fun setPosOffsetPreset(value: Int) = hex("C0A80101000004414041") + value.and(0xFF).toByte()
    fun setNegOffsetPreset(value: Int) = hex("C0A80101000004414042") + value.and(0xFF).toByte()
    val readCenter: ByteArray get() = hex("C0A80101000003514040")
    val readPosOffset: ByteArray get() = hex("C0A80101000003514041")
    val readNegOffset: ByteArray get() = hex("C0A80101000003514042")
    val readInfo: ByteArray get() = hex("C0A8010100000152")
    val reloadDefaults: ByteArray get() = hex("C0A801010000014F")
}

/** A parsed reply from the car. */
sealed interface CarReply {
    data class Battery(val level: Int) : CarReply
    data class Version(val deviceId: String, val model: Int, val firmware: String) : CarReply
    data class Register(val name: String, val value: Int) : CarReply
    data class Unknown(val bytes: ByteArray) : CarReply {
        override fun equals(other: Any?) = other is Unknown && bytes.contentEquals(other.bytes)
        override fun hashCode() = bytes.contentHashCode()
    }
}

/** Decode a received datagram the way the app's mcuDecode / line parser does. */
fun decodeReply(data: ByteArray, len: Int): CarReply? {
    // ASCII line replies (BATT=NN)
    val text = runCatching { String(data, 0, len, Charsets.US_ASCII) }.getOrNull()
    if (text != null && text.contains("BATT=")) {
        text.split("\n").firstOrNull { it.startsWith("BATT=") }?.let { line ->
            line.removePrefix("BATT=").trim().toIntOrNull()?.let { return CarReply.Battery(it) }
        }
    }
    if (len < 8) return null
    return when (data[7].toInt() and 0xFF) {
        0x62 -> if (len >= 16) CarReply.Version(
            deviceId = String(data, 8, 4, Charsets.US_ASCII),
            model = data[12].toInt() and 0xFF,
            firmware = "v%d.%02d%x".format(
                data[13].toInt() and 0xFF, data[14].toInt() and 0xFF, data[15].toInt() and 0xFF
            ),
        ) else null
        0x61 -> if (len >= 11) {
            val addr = ((data[8].toInt() and 0xFF) shl 8) or (data[9].toInt() and 0xFF)
            val name = when (addr) {
                0x4040 -> "center"; 0x4041 -> "+offset"; 0x4042 -> "-offset"; else -> "0x%04x".format(addr)
            }
            CarReply.Register(name, data[10].toInt() and 0xFF)
        } else null
        else -> CarReply.Unknown(data.copyOf(len))
    }
}
