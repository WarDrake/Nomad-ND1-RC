package com.threewd_online.nomad

import android.Manifest
import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.threewd_online.nomad.input.GamepadController
import com.threewd_online.nomad.ui.ControlScreen
import com.threewd_online.nomad.ui.ControlViewModel
import com.threewd_online.nomad.ui.theme.NomadTheme

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
        // Never let the screen sleep while driving.
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        enterImmersive()
        requestWifiPermissions()
        setContent {
            NomadTheme {
                ControlScreen(vm)
            }
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) enterImmersive() // immersive is dropped on focus loss; restore it
    }

    /** Safety: stop the car if the app leaves the foreground (keeps the link alive). */
    override fun onPause() {
        super.onPause()
        vm.stopDrive()
    }

    private fun enterImmersive() {
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        controller.hide(WindowInsetsCompat.Type.systemBars())
        controller.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
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
            // Saving captures on pre-Android-10 needs storage permission.
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
                add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            }
        }.toTypedArray()
        permLauncher.launch(perms)
    }
}
