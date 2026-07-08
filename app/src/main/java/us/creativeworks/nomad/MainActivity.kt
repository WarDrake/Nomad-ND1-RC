package us.creativeworks.nomad

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.viewmodel.compose.viewModel
import us.creativeworks.nomad.ui.ControlScreen
import us.creativeworks.nomad.ui.ControlViewModel

/**
 * Single-activity Compose host. Requests the Wi-Fi/location permissions needed
 * to identify and bind the car's network, then shows the control surface.
 */
class MainActivity : ComponentActivity() {

    private val permLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { /* result surfaced via UI */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestWifiPermissions()
        setContent {
            val vm: ControlViewModel = viewModel()
            ControlScreen(vm)
        }
    }

    private fun requestWifiPermissions() {
        val perms = buildList {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(Manifest.permission.NEARBY_WIFI_DEVICES)
            } else {
                add(Manifest.permission.ACCESS_FINE_LOCATION)
            }
        }.toTypedArray()
        permLauncher.launch(perms)
    }
}
