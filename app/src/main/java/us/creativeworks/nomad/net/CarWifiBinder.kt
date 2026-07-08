package us.creativeworks.nomad.net

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.util.Log
import java.net.DatagramSocket

/**
 * Binds our UDP socket to the car's Wi-Fi network.
 *
 * WHY THIS EXISTS: since Android 10, joining a Wi-Fi AP that has no internet
 * (the car) does NOT make it the default route. Without an explicit bind, UDP
 * to 192.168.0.1 silently leaves via cellular and never reaches the car. This
 * is the #1 behavioral difference from the legacy app and the most common
 * reason a naive rewrite "can't connect".
 *
 * Call [request] after the user has joined SSID NOMAD_ND1-XXXX. The callback
 * fires with the [Network] handle; pass any DatagramSocket to [bindSocket].
 */
class CarWifiBinder(context: Context) {

    private val cm = context.applicationContext
        .getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    private var callback: ConnectivityManager.NetworkCallback? = null

    @Volatile
    var network: Network? = null
        private set

    /**
     * Request the currently-joined Wi-Fi (no-internet capability removed so the
     * car's AP qualifies). [onAvailable] is invoked on a binder thread.
     */
    fun request(onAvailable: (Network) -> Unit, onLost: () -> Unit = {}) {
        release()
        val req = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(net: Network) {
                Log.i(TAG, "Car Wi-Fi network available: $net")
                network = net
                onAvailable(net)
            }
            override fun onLost(net: Network) {
                Log.w(TAG, "Car Wi-Fi network lost: $net")
                network = null
                onLost()
            }
        }
        callback = cb
        cm.requestNetwork(req, cb)
    }

    /** Bind a socket so its traffic is forced over the car's Wi-Fi. */
    fun bindSocket(socket: DatagramSocket): Boolean {
        val net = network ?: return false
        return runCatching { net.bindSocket(socket); true }
            .onFailure { Log.e(TAG, "bindSocket failed", it) }
            .getOrDefault(false)
    }

    fun release() {
        callback?.let { runCatching { cm.unregisterNetworkCallback(it) } }
        callback = null
        network = null
    }

    companion object {
        private const val TAG = "CarWifiBinder"
    }
}
