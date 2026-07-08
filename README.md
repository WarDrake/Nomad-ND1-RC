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
app/                    Android app (Kotlin, Compose, Media3/ExoPlayer)
  control/Protocol.kt   Command builders + reply decoder (the protocol, in code)
  control/NomadClient.kt UDP socket, handshake, keepalive, 10 Hz drive loop
  net/CarWifiBinder.kt  Binds the socket to the car's no-internet Wi-Fi (Android 10+ fix)
  video/VideoPlayer.kt  ExoPlayer RTSP surface (low-latency tuned)
  input/GamepadController.kt  Maps gamepad sticks/buttons to car control
  ui/                   Compose control screen + ViewModel
```

## Current status

| Piece | State |
|---|---|
| Protocol spec | ✅ Complete, from decompiled source |
| UDP control (connect/drive/LED/telemetry) | ✅ Protocol confirmed on a live car (handshake + battery telemetry working via `nomad_probe.py`) |
| Wi-Fi network binding | ✅ Implemented |
| RTSP video | ✅ Stream confirmed + latency-tuned. Car floor ~200ms (ffplay); app ~500ms glass-to-glass |
| Gamepad support | ✅ Left stick steer · triggers throttle · A/B lights · X cam · Y connect (touchscreen still works) |
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
