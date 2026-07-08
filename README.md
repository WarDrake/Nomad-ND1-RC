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
  video/VideoPlayer.kt  ExoPlayer RTSP surface
  ui/                   Compose control screen + ViewModel
```

## Current status

| Piece | State |
|---|---|
| Protocol spec | ✅ Complete, from decompiled source |
| UDP control (connect/drive/LED/telemetry) | ✅ Implemented; **needs on-car validation** |
| Wi-Fi network binding | ✅ Implemented |
| RTSP video | ⚠️ Implemented with ExoPlayer, but the stream **does not yet play** — see below |
| Signing / release build | ⛔ Not set up |

### ⚠️ Open issue: video + car connectivity
As of the last test, `ffplay rtsp://192.168.0.1/vs1` did **not** play, and raw `nc` commands
did not reach the car. The most likely cause of the `nc` failure is documented in
`tools/nomad_probe.py`: the app binds **local UDP port 8234** and the car replies there, so a
random-source-port `nc` never completes the exchange. **Use the probe instead** — it binds
8234 like the real app:

```bash
# On a laptop joined to the car's Wi-Fi (verify you got a 192.168.0.x address first):
python3 tools/nomad_probe.py diagnose      # sweeps addressing, tells you what the car answers
python3 tools/nomad_probe.py connect       # full handshake + keepalive; a BATT= reply = success
python3 tools/nomad_probe.py drive         # interactive driving REPL
```

If `diagnose` gets a `BATT=` reply, the control protocol is confirmed and the Android app
should work. The RTSP video is a separate problem — if VLC/ffplay can't decode `vs1`, we'll
need libVLC or a bundled modern FFmpeg (the original app shipped its own FFmpeg 5.x, which
suggests a nonstandard stream). See NOMAD-ND1-PROTOCOL.md §6.

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
