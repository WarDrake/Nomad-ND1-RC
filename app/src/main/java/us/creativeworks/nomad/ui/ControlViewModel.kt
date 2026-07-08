package us.creativeworks.nomad.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import us.creativeworks.nomad.control.NomadClient
import us.creativeworks.nomad.net.CarWifiBinder

/**
 * Wires the Wi-Fi binder to the control client. The binder must acquire the
 * car's Network before the client's socket is bound, so we request it up front
 * and let [NomadClient] pull the current network at connect time.
 */
class ControlViewModel(app: Application) : AndroidViewModel(app) {

    private val wifi = CarWifiBinder(app)
    val client = NomadClient(bindSocket = { socket -> wifi.bindSocket(socket) })

    init {
        // Start listening for the car's Wi-Fi network immediately.
        wifi.request(onAvailable = { /* available; connect() will bind on demand */ })
    }

    fun connect() = client.connect()
    fun disconnect() = client.disconnect()

    override fun onCleared() {
        client.shutdown()
        wifi.release()
    }
}
