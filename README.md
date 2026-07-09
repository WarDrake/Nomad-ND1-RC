# Nomad ND1 — modern control app

A ground-up rebuild of the control app for the **Nomad ND1** collector's-edition RC car,
replacing the original `com.pdp.MEAndromeda` APK (targetSdk 16, 32-bit only) that no longer
installs on modern Android.

The car ("Mako" internally) is a Wi-Fi access point. The phone joins the car's Wi-Fi and
controls it over UDP; video is an RTSP stream. The full wire protocol — recovered by
decompiling the original APK — is documented in **[NOMAD-ND1-PROTOCOL.md](NOMAD-ND1-PROTOCOL.md)**.

## Repo layout

```
NOMAD-ND1-PROTOCOL.md   Full reverse-engineered protocol spec (authoritative reference)
tools/nomad_probe.py    Python UDP test harness — drive/diagnose the car from a laptop
tools/synth_sfx.py      Regenerates the original synthesized sound effects into res/raw
app/                    Android app (Kotlin, Compose, Media3/ExoPlayer)
  control/Protocol.kt   Command builders + reply decoder (the protocol, in code)
  control/NomadClient.kt UDP socket, handshake, keepalive, 10 Hz drive loop
  net/CarWifiBinder.kt  Binds the socket to the car's no-internet Wi-Fi (Android 10+ fix)
  video/VideoPlayer.kt  ExoPlayer RTSP surface (low-latency tuned)
  input/ControllerProfile.kt  Selectable gamepad mapping presets (add layouts here)
  input/GamepadController.kt  Reads controller events, delegates mapping to the profile
  audio/SoundManager.kt  SoundPool engine loop + UI cues (original synthesized audio)
  ui/theme/             Mass Effect Andromeda / Nomad theme (color, type, shapes)
  ui/DriveControls.kt   Self-centering touch HUD pads (steering + throttle)
  ui/Hud.kt             Status cluster, action buttons, no-signal backdrop
  ui/                   Compose control screen + ViewModel
```

## Current status

| Piece | State |
|---|---|
| Protocol spec | ✅ Complete, from decompiled source |
| UDP control (connect/drive/LED/telemetry) | ✅ Protocol confirmed on a live car (handshake + battery telemetry working via `nomad_probe.py`) |
| Wi-Fi network binding | ✅ Implemented |
| RTSP video | ✅ Stream confirmed + latency-tuned. Car floor ~200ms (ffplay); app ~500ms glass-to-glass |
| Gamepad support | ✅ Selectable controller profiles (Single-stick / Stadia dual-stick), persisted; touchscreen always works |
| UI / theming | ✅ Mass Effect Andromeda / Nomad HUD theme; usable self-centering touch drive pads |
| Photo / video capture | ✅ Still + MP4 capture of the feed (PixelCopy → MediaCodec) to DCIM/NOMAD_ND1 |
| Telemetry HUD | ✅ Signal-strength meter + low-battery warning alongside link state / firmware |
| Auto-reconnect | ✅ Re-runs the handshake on a transient link drop instead of dropping to FAILED |
| Throttle response curve | ✅ Exponential expo curve for fine low-speed control (a curve, not a speed limiter) |
| Sound | ✅ Original synthesized engine drone (throttle-tracked) + UI cues; mute toggle in Setup (`tools/synth_sfx.py`) |
| Android app build | ✅ Builds and runs on-device |
| Signing / release build | ⛔ Not set up |

### Testing against the car (both paths now confirmed working)

`nc` cannot talk to the car because it uses a random source port; the car replies to
port **8234**. Use the probe, which binds 8234 like the real app:

```bash
# On a laptop joined to the car's Wi-Fi (verify you got a 192.168.0.x address first):
python3 tools/nomad_probe.py diagnose      # sweeps addressing, tells you what the car answers
python3 tools/nomad_probe.py connect       # full handshake + keepalive; a BATT= reply = success
python3 tools/nomad_probe.py drive         # interactive driving REPL
```

Control is **confirmed**: the handshake connects and the car returns `BATT=` telemetry.

Video is **confirmed**: with a control session active, the stream plays with
```bash
ffplay -rtsp_transport tcp rtsp://192.168.0.1/vs1     # works
vlc --rtsp-tcp rtsp://192.168.0.1/vs1                 # VLC must be forced to TCP
```
It is a standard LIVE555 H.264 640×480@25fps stream (see `video_log` and
NOMAD-ND1-PROTOCOL.md §6). The car does **not** serve UDP RTP reliably, so the client must
force RTP-over-TCP — which the app does. No custom decoder required.

## Building the Android app

Requires **Android Studio (Ladybug/2024.2+)** or a local Android SDK with JDK 17.

```bash
# The Gradle wrapper JAR is not committed. Generate it once (or just open in Android Studio,
# which does this automatically):
gradle wrapper --gradle-version 8.11.1

# Then:
./gradlew assembleDebug          # build APK
./gradlew installDebug           # install on a connected device
```

Create `local.properties` pointing at your SDK (Android Studio writes this for you):
```
sdk.dir=/path/to/Android/Sdk
```

## License / provenance
Reverse-engineered for interoperability to restore control of legally-owned hardware whose
original app is abandoned. Contains no code copied from the original APK — only the observed
network protocol, which is not copyrightable.
