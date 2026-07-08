package us.creativeworks.nomad

import android.Manifest
import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
import android.view.MotionEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import us.creativeworks.nomad.input.GamepadController
import us.creativeworks.nomad.ui.ControlScreen
import us.creativeworks.nomad.ui.ControlViewModel
import us.creativeworks.nomad.ui.theme.NomadTheme

/**
 * Single-activity Compose host. Requests the Wi-Fi/location permissions needed
 * to identify and bind the car's network, shows the control surface, and forwards
 * gamepad input to the shared ViewModel.
 */
class MainActivity : ComponentActivity() {

    // Same ViewModel instance Compose resolves via viewModel(), so gamepad and UI
    // share state. (viewModel() inside setContent defaults to this Activity's store.)
    private val vm: ControlViewModel by viewModels()
    private val gamepad by lazy { GamepadController(vm) }

    private val permLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { /* surfaced via UI */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestWifiPermissions()
        setContent {
            NomadTheme {
                ControlScreen(vm)
            }
        }
    }

    // Analog sticks / triggers arrive as joystick MotionEvents.
    override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean {
        if (gamepad.onMotion(event)) return true
        return super.dispatchGenericMotionEvent(event)
    }

    // Gamepad buttons arrive as KeyEvents; let the controller consume the ones it maps.
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (gamepad.onKey(event)) return true
        return super.dispatchKeyEvent(event)
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
