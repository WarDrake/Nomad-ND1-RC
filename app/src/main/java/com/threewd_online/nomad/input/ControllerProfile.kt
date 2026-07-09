package com.threewd_online.nomad.input

import android.view.KeyEvent
import android.view.MotionEvent
import kotlin.math.abs

/** A discrete action a gamepad button/trigger can trigger. */
enum class GamepadAction { TOGGLE_UPPER_LED, TOGGLE_LOWER_LED, TOGGLE_CAMERA, TOGGLE_CONNECTION }

/** Normalized drive intent from the sticks/triggers: steer -1..1, forward/reverse 0..1. */
data class DriveIntent(val steer: Float, val forward: Float, val reverse: Float)

/**
 * Controller mapping presets. Each profile decides how joystick axes become a
 * [DriveIntent] and how buttons/triggers map to [GamepadAction]s. Add new layouts
 * by adding an enum constant — the selector UI enumerates [entries] automatically.
 *
 * [leftTriggerAction]/[rightTriggerAction] are non-null when a profile uses the
 * analog triggers as on/off buttons (edge-detected by GamepadController) rather
 * than for throttle.
 */
enum class ControllerProfile(
    val label: String,
    val hint: String,
    val leftTriggerAction: GamepadAction? = null,
    val rightTriggerAction: GamepadAction? = null,
) {
    /** Original layout: one stick + triggers do everything. Kept as-is. */
    SINGLE_STICK(
        label = "Single stick",
        hint = "L-stick steer · triggers throttle · A/B lights · X cam · Y connect",
    ) {
        override fun drive(e: MotionEvent): DriveIntent {
            val steer = dz(e.getAxisValue(MotionEvent.AXIS_X))
            var forward = trigger(e, MotionEvent.AXIS_RTRIGGER, MotionEvent.AXIS_GAS)
            var reverse = trigger(e, MotionEvent.AXIS_LTRIGGER, MotionEvent.AXIS_BRAKE)
            // Fallback for pads without analog triggers: left stick Y (up = forward).
            if (forward < TRIGGER_FLOOR && reverse < TRIGGER_FLOOR) {
                val y = -dz(e.getAxisValue(MotionEvent.AXIS_Y))
                if (y > 0f) forward = y else reverse = -y
            }
            return DriveIntent(steer, forward.coerceIn(0f, 1f), reverse.coerceIn(0f, 1f))
        }

        override fun buttonAction(keyCode: Int): GamepadAction? = when (keyCode) {
            KeyEvent.KEYCODE_BUTTON_A -> GamepadAction.TOGGLE_UPPER_LED
            KeyEvent.KEYCODE_BUTTON_B -> GamepadAction.TOGGLE_LOWER_LED
            KeyEvent.KEYCODE_BUTTON_X -> GamepadAction.TOGGLE_CAMERA
            KeyEvent.KEYCODE_BUTTON_Y,
            KeyEvent.KEYCODE_BUTTON_START -> GamepadAction.TOGGLE_CONNECTION
            else -> null
        }
    },

    /** Dual-stick layout for a Stadia-style gamepad. */
    STADIA(
        label = "Stadia",
        hint = "L-stick steer · R-stick throttle · L/R triggers lights · Menu cam · Start connect",
        leftTriggerAction = GamepadAction.TOGGLE_UPPER_LED,
        rightTriggerAction = GamepadAction.TOGGLE_LOWER_LED,
    ) {
        override fun drive(e: MotionEvent): DriveIntent {
            val steer = dz(e.getAxisValue(MotionEvent.AXIS_X))          // left stick X
            val ry = -dz(e.getAxisValue(MotionEvent.AXIS_RZ))           // right stick Y (up = +)
            val forward = if (ry > 0f) ry else 0f
            val reverse = if (ry < 0f) -ry else 0f
            return DriveIntent(steer, forward, reverse)
        }

        override fun buttonAction(keyCode: Int): GamepadAction? = when (keyCode) {
            KeyEvent.KEYCODE_BUTTON_START -> GamepadAction.TOGGLE_CONNECTION
            // "Menu" varies by controller; accept the usual candidates.
            KeyEvent.KEYCODE_BUTTON_SELECT,
            KeyEvent.KEYCODE_MENU,
            KeyEvent.KEYCODE_BUTTON_MODE -> GamepadAction.TOGGLE_CAMERA
            else -> null
        }
    };

    /** Map current joystick axes to a normalized drive intent. */
    abstract fun drive(e: MotionEvent): DriveIntent

    /** Map a button keyCode to an action, or null if this profile ignores it. */
    abstract fun buttonAction(keyCode: Int): GamepadAction?

    companion object {
        const val STICK_FLAT = 0.12f
        const val TRIGGER_FLOOR = 0.05f

        fun fromNameOrDefault(name: String?): ControllerProfile =
            ControllerProfile.entries.firstOrNull { it.name == name } ?: SINGLE_STICK
    }
}

// --- Shared axis helpers -----------------------------------------------------
private fun dz(v: Float): Float = if (abs(v) < ControllerProfile.STICK_FLAT) 0f else v

private fun trigger(e: MotionEvent, primary: Int, fallback: Int): Float {
    val v = e.getAxisValue(primary)
    return if (v != 0f) v else e.getAxisValue(fallback)
}
