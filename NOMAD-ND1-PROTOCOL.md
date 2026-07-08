# Nomad ND1 — Control Protocol Specification

Reverse-engineered from `com.pdp.MEAndromeda` v0.4.05 (build 405) by decompiling the
original Android APK. This document is the reference for building a modern replacement
app. Everything here was recovered from the app's own source — it describes what the
**car firmware expects to receive**, so it is authoritative for the client side.

> Status of each section: **CONFIRMED** = read directly from decompiled code.
> **INFERRED** = deduced from packet structure, should be validated against a live car.

---

## 1. Network overview

The car (internally codenamed **"Mako"**) is a self-hosted Wi-Fi Access Point. The phone
joins the car's Wi-Fi, and all communication happens over that LAN. There is no cloud, no
pairing server, no Bluetooth.

| Item | Value | Notes |
|---|---|---|
| Car Wi-Fi SSID | `NOMAD_ND1-XXXX` | App validates SSID *contains* `NOMAD_ND1-`. Suffix is per-unit. |
| Car IP address | `192.168.0.1` | Constant `Mako_IP`. Car is the AP/gateway. |
| Control port | **UDP 8234** | Constant `Mako_Port`. Same port used for send **and** receive. |
| Video | `rtsp://192.168.0.1/vs1` | H.264 over RTSP. See §6. |
| Firmware update | FTP port 21, anonymous | Separate path, see §7. Not needed for driving. |
| Wi-Fi signal | Read locally via Android `WifiManager` RSSI | Purely a UI readout; not part of the car protocol. |

**Socket model (CONFIRMED):** the app opens a single `DatagramSocket` bound to local
port 8234, and both sends commands to `192.168.0.1:8234` and receives telemetry on that
same socket. Replicate this exactly — bind local 8234, don't use an ephemeral port.

---

## 2. Command packet format

Two distinct kinds of UDP payloads are sent. **Both go to `192.168.0.1:8234`.**

### 2a. ASCII string commands (CONFIRMED)
The literal ASCII bytes of the string, no terminator, no length prefix.
Sent by `PacketSendString()`.

| String | Meaning |
|---|---|
| `MAKO_CONNECT` | Handshake / begin session. App sends this **10 times, 50 ms apart** on connect. |
| `MAKO_DISCONNECT` | End session. |
| `MAKO_READ_BATT` | Request battery level. App polls this every **6000 ms**. |
| `MAKO_VERSION` | Request firmware/device version. Reply parsed as binary CMD `0x62`, see §5. |
| `MAKO_MACADD` | Request MAC address. |
| `MAKO_LED1_ON` / `MAKO_LED1_OFF` | Upper headlights on/off. |
| `MAKO_LED2_ON` / `MAKO_LED2_OFF` | Lower/under lights on/off. |

### 2b. Binary (hex) command packets (CONFIRMED structure, INFERRED field names)
Built by `hexStringToByteArray(hexString)` then sent by `PacketSend()`. In the source these
are written as hex **strings** like `"C0A8010100000421..."`; each pair of hex chars is one
byte on the wire.

Observed layout (11-ish bytes for drive):

```
byte:   0    1    2    3    4    5    6    7    8    9    10  ...
value:  C0   A8   01   01   00   00   LL   CMD  <payload bytes...>
        └──────── fixed 8-byte header ────────┘
```

- **Bytes 0–3 = `C0 A8 01 01`** — constant prefix on *every* binary command. This is the
  literal bytes for `192.168.1.1`. It is a fixed protocol magic/header, **not** the
  destination (the real destination is `192.168.0.1`). Send it verbatim; do not "fix" it.
- **Bytes 4–5 = `00 00`** — constant in all observed commands.
- **Byte 6 = `LL`** — a length/group selector. Observed: `0x04` for drive & steering-preset
  commands, `0x03` for register-read queries, `0x01` for single-opcode commands. (INFERRED:
  likely "payload length" but treat as a fixed part of each opcode below.)
- **Byte 7 = `CMD`** — the opcode. This is the same index the car uses in its replies
  (see §5, where `receiveByte[7]` is read as CMD).
- **Bytes 8+ = payload**, opcode-specific.

You do not need to synthesize these from first principles — every command the app can send
is enumerated in §3 and §4 with its exact hex template.

---

## 3. Drive command (the core of the app) — CONFIRMED

This is the only command that actually moves the car.

```
Template:  C0A8010100000421 %02x %02x %02x
                            └FW─┘└BW─┘└ST─┘
Opcode (byte 7) = 0x21
Payload (bytes 8,9,10) = [ forwardPWM, backwardPWM, steeringLevel ]  (one byte each)
```

- `forwardPWM`  (`motorFwPwm`): 0 = stop … up to ~230 full forward.
- `backwardPWM` (`motorBwPwm`): 0 = stop … up to ~230 full reverse.
- `steeringLevel` (`steeringLv`): steering position, **centered on 128** (0x80). Range is
  center ± 82, i.e. roughly **46 … 210** (0x2E … 0xD2). Higher = one direction, lower = the other.

**Only one of FW/BW is non-zero at a time** for normal driving; the other is 0.
(`255/255` is used as a special brake pulse — see §3c.)

### 3a. Send cadence (CONFIRMED — important)
While the user is actively driving, the app runs a `Timer` (`MyTimerTask`) that fires
**every 100 ms** and sends the current drive packet. The car is driven by a continuous
stream of these packets, not one-shot commands. When the user releases both sticks the
timer is cancelled and a centering packet is sent (§3b).

Implement a fixed **10 Hz send loop** while any control is engaged.

### 3b. Steering center / neutral (CONFIRMED)
On connect, and when the steering returns to center with no throttle, the app calls
`sendPacketAlignCenter()` which sends **twice, 100 ms apart**:

```
C0A80101000004210000 %02x      // FW=00, BW=00, steer=<current steeringLv>
```

i.e. the same drive opcode `0x21` with both motors at 0.

### 3c. Reverse-direction brake pulse (CONFIRMED, unusual — replicate carefully)
If the user commands reverse within 500 ms of moving forward (or vice-versa), the app
injects a hard brake: it sends drive packets with **FW=0xFF and BW=0xFF (255/255)** a few
times ~10 ms apart, then returns to 0/0. This is an intentional "instant stop before
changing direction" behavior (`reverse_move` logic). A faithful port should reproduce it;
a minimal port can omit it (the car just won't have the abrupt-stop feel).

### 3d. Input→PWM mapping (CONFIRMED, optional to copy exactly)
The UI used two vertical/horizontal seek bars (range 0–500, center 250) with a dead zone.
Speed is a non-linear curve:

```
ratio    = abs(progress - 250) / 250
speedLv  = round( (1 - (1 - ratio)^2.0) * 230 )     // functionPower=2.0, speedTotalStep=230
```
Steering:
```
ratio       = progress / 500
steeringLv  = round( 164 * ratio + (steeringCenter - 82) )   // steeringTotalStep=164
```
Dead-zone: movements smaller than 40 units (`seekBarthumbActiveValue`) are ignored.
`boostValue=178` is only a sound/FX threshold. You can replace this entire mapping with
whatever control UI you build (buttons, on-screen joystick, gamepad) as long as you emit
the byte values in §3.

### 3e. Steering trim (CONFIRMED)
Trim shifts the steering center so a car that pulls to one side can be corrected:
```
steeringCenter = 128 + trimIndex * 2          // trimIndex in [-15, +15], default 0
```
Persisted in SharedPreferences as `TrimIndex`. After changing trim the app re-sends center.

---

## 4. Configuration / query commands (CONFIRMED templates)

These are only exposed in the app's hidden "engineering/settings" mode. Not required to
drive, but documented for completeness.

| Purpose | Hex template | Opcode |
|---|---|---|
| Set steering **center** preset | `C0A80101000004414040` + `<2 hex>` | `0x41`, addr `0x4040` |
| Set steering **+offset** preset | `C0A80101000004414041` + `<2 hex>` | `0x41`, addr `0x4041` |
| Set steering **−offset** preset | `C0A80101000004414042` + `<2 hex>` | `0x41`, addr `0x4042` |
| Read center register | `C0A80101000003514040` | `0x51`, addr `0x4040` |
| Read +offset register | `C0A80101000003514041` | `0x51`, addr `0x4041` |
| Read −offset register | `C0A80101000003514042` | `0x51`, addr `0x4042` |
| Read Nomad info | `C0A8010100000152` | `0x52` |
| Reload factory defaults | `C0A801010000014F` | `0x4F` |

The `<2 hex>` suffix is a single byte the user typed (e.g. `80`, `0d`, `00`).

---

## 5. Telemetry / replies FROM the car (CONFIRMED)

The app receives on the same UDP socket. Two reply formats arrive:

### 5a. ASCII line replies
Newline (`\n`)-delimited ASCII. The only field the app parses:

- **`BATT=<n>`** — battery level. On receiving this the app resets a 60 s keep-alive timer
  and averages the last 15 readings for the battery icon. `<n>` is parsed as a decimal int.

Receiving **any** `BATT=` line is also the app's proof the car is alive; see §8.

### 5b. Binary replies — parsed by `mcuDecode(bytes, len)`
Same 8-byte header shape; **byte 7 is the opcode**:

**Opcode `0x62` ('b') — version/identity** (reply to `MAKO_VERSION`):
```
bytes 8–11 : device ID, 4 ASCII characters
byte 12    : device model  (shown as 0xNN)
byte 13    : fw major
byte 14    : fw minor (2-digit decimal)
byte 15    : fw sub  (1 hex digit)     → displayed "v{13}.{14}{15}"
```

**Opcode `0x61` ('a') — register read reply** (reply to the `0x51` queries):
```
bytes 8–9 : ADDRESS (big-endian):  0x4040=center, 0x4041=+offset, 0x4042=−offset
byte 10   : the stored register value
```

Packets whose length is exactly **1024 bytes are ignored** (treated as non-telemetry).

You only need §5a (battery/liveness) for a functional driving app; §5b is for the
settings screen.

---

## 6. Video (RTSP) — CONFIRMED transport, decoder is the main port risk

- URL: **`rtsp://192.168.0.1/vs1`**
- The original app did **not** use Android's media stack. It bundled **FFmpeg 5.x**
  (`libavcodec-56`, `libavformat-56`, `libavfilter-5`, `libswscale`, `libswresample`) plus a
  custom JNI wrapper `libaveronix_jni.so` / `libGetJNILib.so`, and rendered decoded frames
  to an OpenGL ES 2.0 surface (`videoGLView`/`videoGLRenderer`, `naInit/naGetVideoRes/
  naSetup/naPlay/naStop`).
- Native code was **32-bit only** (`armeabi`, `x86`). No arm64 — a primary reason the APK
  won't run on modern phones.

**For the rebuild:** do NOT reuse the old FFmpeg blobs. Try, in order:
1. **AndroidX Media3 / ExoPlayer** with the RTSP module — zero native code, handles
   standard H.264 RTSP.
2. **libVLC for Android** — more tolerant of nonstandard RTSP/RTP dialects.
3. Only if both choke on `vs1`: bundle a modern FFmpeg (e.g. `ffmpeg-kit`) built for
   `arm64-v8a`.

⚠️ **This is the one genuine unknown.** Because the app shipped its own decoder, the stream
*may* be a nonstandard RTSP/RTP variant that off-the-shelf players reject. **Validate first**
by pointing `ffplay`/VLC at `rtsp://192.168.0.1/vs1` on a laptop joined to the car's Wi-Fi.
The result decides whether video is a 1-hour ExoPlayer drop-in or a multi-day native task.

---

## 7. Firmware update (CONFIRMED — out of scope for driving)

Independent of the control protocol. Over **FTP (port 21, anonymous login)** the app uploads,
from `/<sdcard>/nomad/`: `md5.txt`, `root.sqsh4`, `usr.jffs2`, `usr.sqsh4` to the car's `/`.
Not needed for a control-only rebuild; skip it in v1.

---

## 8. Session lifecycle (CONFIRMED — must replicate for a stable connection)

The exact sequence the app performs, in order:

1. **Bind** `DatagramSocket` to local UDP 8234; start the receive thread.
2. **Connect handshake:** send `MAKO_CONNECT` **10× at 50 ms intervals**.
3. Wait up to **4000 ms** for the car to start replying (a `BATT=` line confirms liveness).
   If nothing arrives, the app declares "connection failed."
4. On success: send an initial **center** packet (§3b), then start three timers:
   - drive-send loop @ **100 ms** (only while a control is engaged),
   - battery poll: send `MAKO_READ_BATT` @ **6000 ms**,
   - **keep-alive watchdog @ 60000 ms**: it is *reset every time a `BATT=` reply arrives*.
     If 60 s pass with no `BATT=`, the app declares "connection lost" and disconnects.
5. **Disconnect:** cancel all timers, send `MAKO_DISCONNECT`, close the socket.

**Keep-alive takeaway:** as long as you keep polling `MAKO_READ_BATT` every 6 s and the car
keeps answering `BATT=`, the link stays up. Miss replies for 60 s → dead.

---

## 9. The critical modern-Android gotcha: Wi-Fi routing

Since **Android 10**, joining a Wi-Fi that has **no internet** (like the car's AP) does *not*
make it the default route. Your UDP packets to `192.168.0.1` will silently go out the
**cellular** interface and never reach the car. The old app (targetSdk 16) never had this
problem; your rebuild absolutely will.

**Fix (required):** explicitly bind the socket to the car's Wi-Fi network:
```kotlin
val req = NetworkRequest.Builder()
    .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
    .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)   // AP has no internet
    .build()
connectivityManager.requestNetwork(req, object : NetworkCallback() {
    override fun onAvailable(network: Network) {
        network.bindSocket(datagramSocket)   // now UDP goes over the car's Wi-Fi
        // ... or connectivityManager.bindProcessToNetwork(network)
    }
})
```
This, not the protocol, is the single biggest behavioral difference from the legacy app.

---

## 10. Rebuild requirements summary

**Manifest / build targets**
- minSdk ≥ 24 (Android 7); target the current API (34/35). Original was min/target **16**.
- OpenGL ES 2.0 was required only for the old custom video renderer; not needed with ExoPlayer.
- Landscape orientation (`screenOrientation="0"` in the original).

**Permissions needed on modern Android**
- `INTERNET`, `ACCESS_WIFI_STATE`, `ACCESS_NETWORK_STATE`, `CHANGE_NETWORK_STATE`
- `ACCESS_FINE_LOCATION` and/or `NEARBY_WIFI_DEVICES` (API 33+) — required to read SSID and
  to programmatically join/identify the car's Wi-Fi.
- (Legacy `WRITE_EXTERNAL_STORAGE`, `RECORD_AUDIO`, `RECORD_VIDEO` were for photo/recording
  features — optional for v1.)

**Effort estimate**
- Control protocol (§2–§5, §8): fully specified above, low risk — a few days.
- Wi-Fi binding (§9): fiddly but well-trodden — ~1 day.
- Video (§6): the wildcard — 1 hour (ExoPlayer works) to several days (needs native decode).
  **Test the RTSP stream before committing to a video approach.**

---

## Appendix A — quick reference: every packet the app sends

```
# Strings (raw ASCII to 192.168.0.1:8234)
MAKO_CONNECT            # 10x @50ms on connect
MAKO_DISCONNECT
MAKO_READ_BATT          # every 6000ms
MAKO_VERSION
MAKO_MACADD
MAKO_LED1_ON | MAKO_LED1_OFF     # upper lights
MAKO_LED2_ON | MAKO_LED2_OFF     # lower lights

# Binary (hex → bytes to 192.168.0.1:8234)
C0A8010100000421 FF FF ST        # drive: forwardPWM backwardPWM steeringLevel
C0A80101000004210000 ST          # neutral/center (motors 0)
C0A80101000004414040 VV          # set center preset (VV=byte)
C0A80101000004414041 VV          # set +offset preset
C0A80101000004414042 VV          # set -offset preset
C0A80101000003514040             # read center register
C0A80101000003514041             # read +offset register
C0A80101000003514042             # read -offset register
C0A8010100000152                 # read nomad info
C0A801010000014F                 # reload defaults
```

## Appendix B — how to re-derive / verify this

Source was recovered with:
```
jadx -d out <apk>           # decompile dex → Java  (all logic is in
                            #   com/pdp/MEAndromeda/MainActivity.java)
```
Key symbols to cross-check against a live car capture (Wireshark on the car's Wi-Fi,
filter `udp.port == 8234`): `Mako_Port`, `Mako_IP`, `PacketSend`, `PacketSendString`,
`sendPacketAlignCenter`, `mcuDecode`, `MyTimerTask`, `keepaliveTask`, `getBattTask`.
