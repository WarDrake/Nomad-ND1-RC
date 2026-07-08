package us.creativeworks.nomad.control

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress

enum class ConnectionState { DISCONNECTED, CONNECTING, CONNECTED, FAILED }

data class CarStatus(
    val connection: ConnectionState = ConnectionState.DISCONNECTED,
    val battery: Int? = null,
    val firmware: String? = null,
    val deviceId: String? = null,
    val lastReplyAgoMs: Long? = null,
)

/**
 * Owns the UDP control socket and reproduces the app's session lifecycle:
 * bind local 8234 -> MAKO_CONNECT x10 -> wait for a BATT reply -> keepalive +
 * 10 Hz drive stream. See NOMAD-ND1-PROTOCOL.md.
 *
 * @param bindSocket hook that binds the socket to the car's Wi-Fi Network
 *        (see [us.creativeworks.nomad.net.CarWifiBinder]); return false if no
 *        car network is available so we can surface a clear error.
 */
class NomadClient(
    private val bindSocket: (DatagramSocket) -> Boolean,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val carAddr: InetAddress by lazy { InetAddress.getByName(Protocol.CAR_IP) }

    private var socket: DatagramSocket? = null
    private var rxJob: Job? = null
    private var battJob: Job? = null
    private var driveJob: Job? = null

    private val _status = MutableStateFlow(CarStatus())
    val status: StateFlow<CarStatus> = _status.asStateFlow()

    // Current drive intent, streamed continuously at 10 Hz while connected.
    @Volatile private var forwardPwm = 0
    @Volatile private var backwardPwm = 0
    @Volatile private var steering = Protocol.STEER_CENTER_DEFAULT
    @Volatile private var lastReplyAt = 0L

    /** Steering trim center (128 default; adjust +/- to correct pull). */
    @Volatile var steerCenter = Protocol.STEER_CENTER_DEFAULT

    // --- Public control surface ----------------------------------------------

    fun connect() {
        if (_status.value.connection == ConnectionState.CONNECTING) return
        scope.launch { runConnect() }
    }

    fun disconnect() {
        scope.launch {
            runCatching { sendString(Protocol.MAKO_DISCONNECT) }
            teardown(ConnectionState.DISCONNECTED)
        }
    }

    /**
     * Set the current drive intent. This only updates the streamed values — the
     * drive loop sends them (including zeros) continuously at 10 Hz, so releasing
     * a control streams a stop within one tick. We must never simply *stop*
     * sending: the car holds the last command it received, so silence = keep going.
     */
    fun setDrive(forward: Int, backward: Int, steer: Int) {
        forwardPwm = forward.coerceIn(0, 255)
        backwardPwm = backward.coerceIn(0, 255)
        steering = steer.coerceIn(0, 255)
    }

    fun throttle(pwm: Int) = setDrive(pwm.coerceAtLeast(0), 0, steering)
    fun reverse(pwm: Int) = setDrive(0, pwm.coerceAtLeast(0), steering)
    fun steer(level: Int) = setDrive(forwardPwm, backwardPwm, level)
    fun stop() = setDrive(0, 0, steerCenter)

    fun upperLed(on: Boolean) = fireAndForget(if (on) Protocol.MAKO_LED1_ON else Protocol.MAKO_LED1_OFF)
    fun lowerLed(on: Boolean) = fireAndForget(if (on) Protocol.MAKO_LED2_ON else Protocol.MAKO_LED2_OFF)
    fun requestVersion() = fireAndForget(Protocol.MAKO_VERSION)

    fun shutdown() {
        scope.cancel()
        closeSocket()
    }

    // --- Session lifecycle ---------------------------------------------------

    private suspend fun runConnect() {
        teardown(ConnectionState.CONNECTING)
        val sock = DatagramSocket(null as java.net.SocketAddress?).apply {
            reuseAddress = true
            // Bind to the car's Wi-Fi BEFORE binding the local port so traffic routes correctly.
            if (!bindSocket(this)) {
                Log.e(TAG, "No car Wi-Fi network to bind to")
            }
            bind(InetSocketAddress(Protocol.PORT))  // local port 8234, like the app
            soTimeout = 500
        }
        socket = sock
        lastReplyAt = 0L
        startReceive(sock)

        // Handshake: MAKO_CONNECT x10 @ 50ms
        repeat(Protocol.CONNECT_BURST) {
            runCatching { sendString(Protocol.MAKO_CONNECT) }
            delay(Protocol.CONNECT_BURST_INTERVAL_MS)
        }
        runCatching { sendString(Protocol.MAKO_READ_BATT) }

        // Wait for the first reply.
        val deadline = System.currentTimeMillis() + Protocol.CONNECT_WAIT_MS
        while (System.currentTimeMillis() < deadline && lastReplyAt == 0L) delay(100)

        if (lastReplyAt == 0L) {
            Log.w(TAG, "No reply within ${Protocol.CONNECT_WAIT_MS}ms — connection failed")
            teardown(ConnectionState.FAILED)
            return
        }

        _status.value = _status.value.copy(connection = ConnectionState.CONNECTED)
        steering = steerCenter
        runCatching { send(Protocol.center(steering)) }
        requestVersion()
        startBatteryPoll()
        startDriveLoop()
    }

    private fun startReceive(sock: DatagramSocket) {
        rxJob = scope.launch {
            val buf = ByteArray(4096)
            while (isActive && !sock.isClosed) {
                val packet = DatagramPacket(buf, buf.size)
                val ok = runCatching { sock.receive(packet); true }
                    .getOrElse { if (sock.isClosed) return@launch else false }
                if (!ok) continue
                if (packet.length == 1024) continue   // app ignores 1024-byte frames
                lastReplyAt = System.currentTimeMillis()
                decodeReply(packet.data, packet.length)?.let(::onReply)
            }
        }
    }

    private fun onReply(reply: CarReply) {
        _status.value = when (reply) {
            is CarReply.Battery -> _status.value.copy(
                battery = reply.level, lastReplyAgoMs = 0, connection = ConnectionState.CONNECTED
            )
            is CarReply.Version -> _status.value.copy(
                firmware = reply.firmware, deviceId = reply.deviceId
            )
            is CarReply.Register -> _status.value  // surfaced in settings screen later
            is CarReply.Unknown -> _status.value
        }
    }

    private fun startBatteryPoll() {
        battJob = scope.launch {
            while (isActive) {
                runCatching { sendString(Protocol.MAKO_READ_BATT) }
                // Keepalive watchdog: no reply for 60s => dead.
                if (lastReplyAt != 0L &&
                    System.currentTimeMillis() - lastReplyAt > Protocol.KEEPALIVE_TIMEOUT_MS
                ) {
                    Log.w(TAG, "Keepalive timeout — lost connection")
                    teardown(ConnectionState.FAILED)
                    return@launch
                }
                delay(Protocol.BATTERY_POLL_MS)
            }
        }
    }

    private fun startDriveLoop() {
        driveJob = scope.launch {
            // Stream the current command continuously while connected. Neutral is
            // (0,0,center), so a released control actively tells the car to stop
            // rather than leaving it on the last non-zero command.
            while (isActive) {
                runCatching { send(Protocol.drive(forwardPwm, backwardPwm, steering)) }
                delay(Protocol.DRIVE_INTERVAL_MS)
            }
        }
    }

    // --- Socket I/O ----------------------------------------------------------

    private suspend fun send(bytes: ByteArray) = withContext(Dispatchers.IO) {
        socket?.send(DatagramPacket(bytes, bytes.size, carAddr, Protocol.PORT))
    }

    private suspend fun sendString(s: String) = send(s.toByteArray(Charsets.US_ASCII))

    private fun fireAndForget(s: String) {
        scope.launch { runCatching { sendString(s) } }
    }

    private fun teardown(newState: ConnectionState) {
        // Reset intent to neutral so a later reconnect never resumes on a stale command.
        forwardPwm = 0
        backwardPwm = 0
        steering = steerCenter
        battJob?.cancel(); battJob = null
        driveJob?.cancel(); driveJob = null
        rxJob?.cancel(); rxJob = null
        closeSocket()
        _status.value = _status.value.copy(connection = newState)
    }

    private fun closeSocket() {
        runCatching { socket?.close() }
        socket = null
    }

    companion object {
        private const val TAG = "NomadClient"
    }
}
