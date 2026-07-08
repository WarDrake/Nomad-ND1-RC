#!/usr/bin/env python3
"""
nomad_probe.py — Nomad ND1 RC car UDP control probe / test harness.

Replicates the *exact* socket behavior of the original Android app so the car
can be exercised and diagnosed from a laptop (no phone / no APK needed).

Why `nc` almost certainly failed
--------------------------------
The app binds its UDP socket to LOCAL port 8234 and both sends to and receives
from 192.168.0.1:8234. The car firmware replies to port 8234. A plain
`nc -u 192.168.0.1 8234` uses a RANDOM ephemeral source port, so:
  * the car's reply (sent to :8234) never lands back in your nc, and
  * the firmware may ignore control traffic that isn't sourced from :8234.
This tool fixes that by binding local port 8234 (SO_REUSEADDR/REUSEPORT), the
single most likely reason for "car doesn't connect".

Also: the movement/config commands are *binary* (hex → raw bytes). Sending the
literal ASCII text "C0A8..." (as you would by typing it into nc) sends the wrong
bytes entirely. This tool converts hex templates to raw bytes like the app does.

Usage
-----
  # 1. Join the car's Wi-Fi (SSID NOMAD_ND1-XXXX). Confirm you got a 192.168.0.x IP.
  # 2. Full connect + listen (this is the real test):
  python3 nomad_probe.py connect

  # Just listen for any unsolicited traffic from the car:
  python3 nomad_probe.py listen

  # Send one raw string command and wait for a reply:
  python3 nomad_probe.py send MAKO_VERSION

  # Send one raw hex command (auto-converted to bytes):
  python3 nomad_probe.py hex C0A8010100000421 00 00 80

  # Diagnostic sweep — tries several source ports / addressing to find what the car answers:
  python3 nomad_probe.py diagnose

  # Interactive driving REPL (arrow-key-ish text commands):
  python3 nomad_probe.py drive

Options:
  --car-ip 192.168.0.1     car address (default)
  --port 8234              control port (default)
  --local-port 8234        local bind port (default; 0 = ephemeral, like nc)
  --bind 0.0.0.0           local bind address
  --timeout 4.0            seconds to wait for replies in one-shot modes
"""

import argparse
import socket
import sys
import threading
import time

DEFAULT_CAR_IP = "192.168.0.1"
DEFAULT_PORT = 8234

# --- Command templates recovered from the decompiled app ---------------------
STRING_CMDS = {
    "connect": "MAKO_CONNECT",
    "disconnect": "MAKO_DISCONNECT",
    "batt": "MAKO_READ_BATT",
    "version": "MAKO_VERSION",
    "mac": "MAKO_MACADD",
    "led1_on": "MAKO_LED1_ON",
    "led1_off": "MAKO_LED1_OFF",
    "led2_on": "MAKO_LED2_ON",
    "led2_off": "MAKO_LED2_OFF",
}

DRIVE_HEADER = "C0A8010100000421"  # + FW(1) BW(1) STEER(1)
STEER_CENTER = 0x80                 # 128


def drive_hex(fw: int, bw: int, steer: int) -> str:
    """Build a drive command hex string: forwardPWM, backwardPWM, steeringLevel."""
    for v, name in ((fw, "fw"), (bw, "bw"), (steer, "steer")):
        if not 0 <= v <= 255:
            raise ValueError(f"{name}={v} out of byte range 0..255")
    return f"{DRIVE_HEADER}{fw:02x}{bw:02x}{steer:02x}"


def hexstr_to_bytes(s: str) -> bytes:
    """Mirror the app's hexStringToByteArray: concatenated hex pairs -> bytes."""
    s = "".join(s.split()).replace("0x", "")
    if len(s) % 2:
        raise ValueError(f"hex string has odd length: {s!r}")
    return bytes.fromhex(s)


def pretty(data: bytes) -> str:
    hexs = " ".join(f"{b:02X}" for b in data)
    asc = "".join(chr(b) if 32 <= b < 127 else "." for b in data)
    return f"[{len(data):>4}B] {hexs}   |{asc}|"


# --- Socket wrapper ----------------------------------------------------------
class NomadSocket:
    def __init__(self, car_ip, port, local_port, bind_addr):
        self.dest = (car_ip, port)
        self.sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        self.sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        # SO_REUSEPORT lets you run the probe alongside a capture; harmless if unsupported.
        if hasattr(socket, "SO_REUSEPORT"):
            try:
                self.sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEPORT, 1)
            except OSError:
                pass
        # Allow directed broadcast attempts in diagnose mode.
        self.sock.setsockopt(socket.SOL_SOCKET, socket.SO_BROADCAST, 1)
        self.sock.bind((bind_addr, local_port))
        self.bound = self.sock.getsockname()
        print(f"[socket] bound local {self.bound[0]}:{self.bound[1]}  ->  car {self.dest[0]}:{self.dest[1]}")

    def send_str(self, s: str):
        self.sock.sendto(s.encode("ascii"), self.dest)
        print(f"[ tx  ] str  {s!r}")

    def send_hex(self, hexstr: str):
        data = hexstr_to_bytes(hexstr)
        self.sock.sendto(data, self.dest)
        print(f"[ tx  ] hex  {pretty(data)}")

    def send_raw(self, data: bytes, dest=None):
        self.sock.sendto(data, dest or self.dest)

    def close(self):
        try:
            self.sock.close()
        except OSError:
            pass


def decode_reply(addr, data: bytes):
    """Interpret a reply the way the app's mcuDecode / string parser does."""
    src = f"{addr[0]}:{addr[1]}"
    # ASCII line replies (e.g. BATT=NN)
    try:
        text = data.decode("ascii")
        if "BATT=" in text:
            for line in text.split("\n"):
                if line.startswith("BATT="):
                    print(f"[ rx  ] <{src}> BATTERY = {line[5:].strip()}   (car is ALIVE)")
            return
        if text.isprintable():
            print(f"[ rx  ] <{src}> ascii: {text!r}")
            return
    except UnicodeDecodeError:
        pass
    # Binary replies: byte[7] is the opcode
    note = ""
    if len(data) >= 8:
        cmd = data[7]
        if cmd == 0x62 and len(data) >= 16:  # 'b' version/identity
            dev_id = "".join(chr(b) for b in data[8:12])
            note = f"  VERSION dev_id={dev_id!r} model=0x{data[12]:02x} fw=v{data[13]}.{data[14]:02d}{data[15]:x}"
        elif cmd == 0x61 and len(data) >= 11:  # 'a' register read
            address = (data[8] << 8) | data[9]
            reg = {0x4040: "center", 0x4041: "+offset", 0x4042: "-offset"}.get(address, hex(address))
            note = f"  REGISTER {reg} = 0x{data[10]:02x}"
        else:
            note = f"  (opcode 0x{cmd:02x})"
    print(f"[ rx  ] <{src}> {pretty(data)}{note}")


# --- Modes -------------------------------------------------------------------
def run_listener(ns: NomadSocket, stop: threading.Event):
    ns.sock.settimeout(0.5)
    while not stop.is_set():
        try:
            data, addr = ns.sock.recvfrom(4096)
        except socket.timeout:
            continue
        except OSError:
            break
        decode_reply(addr, data)


def mode_connect(ns: NomadSocket, args):
    """Full handshake exactly like the app: 10x MAKO_CONNECT @50ms, then keepalive poll."""
    stop = threading.Event()
    t = threading.Thread(target=run_listener, args=(ns, stop), daemon=True)
    t.start()

    print("\n=== HANDSHAKE: sending MAKO_CONNECT x10 @ 50ms ===")
    for _ in range(10):
        ns.send_str("MAKO_CONNECT")
        time.sleep(0.05)

    print("\n=== waiting up to 4s for the car to answer (a BATT= line = success) ===")
    ns.send_str("MAKO_READ_BATT")
    time.sleep(4.0)

    print("\n=== entering keepalive loop: MAKO_READ_BATT every 6s. Ctrl-C to stop. ===")
    try:
        while True:
            ns.send_str("MAKO_READ_BATT")
            time.sleep(6.0)
    except KeyboardInterrupt:
        print("\n[connect] sending MAKO_DISCONNECT")
        ns.send_str("MAKO_DISCONNECT")
    finally:
        stop.set()


def mode_listen(ns: NomadSocket, args):
    print("=== passive listen. Ctrl-C to stop. ===")
    stop = threading.Event()
    t = threading.Thread(target=run_listener, args=(ns, stop), daemon=True)
    t.start()
    try:
        while True:
            time.sleep(1)
    except KeyboardInterrupt:
        stop.set()


def _oneshot_wait(ns: NomadSocket, timeout: float):
    stop = threading.Event()
    t = threading.Thread(target=run_listener, args=(ns, stop), daemon=True)
    t.start()
    time.sleep(timeout)
    stop.set()


def mode_send(ns: NomadSocket, args):
    cmd = args.args[0] if args.args else ""
    ns.send_str(cmd)
    _oneshot_wait(ns, args.timeout)


def mode_hex(ns: NomadSocket, args):
    ns.send_hex("".join(args.args))
    _oneshot_wait(ns, args.timeout)


def mode_diagnose(ns_unused, args):
    """
    Systematic sweep to find *any* response from the car. Tries the two things
    most likely to matter: source port (8234 vs ephemeral) and unicast vs the
    directed broadcast for the /24. Prints a verdict.
    """
    print("=== DIAGNOSE: probing which addressing the car answers ===\n")
    attempts = [
        ("bind :8234 -> unicast car",      args.local_port or DEFAULT_PORT, args.car_ip),
        ("bind ephemeral -> unicast car",  0,                                args.car_ip),
        ("bind :8234 -> subnet broadcast", args.local_port or DEFAULT_PORT, "192.168.0.255"),
    ]
    any_reply = False
    for label, lport, dest_ip in attempts:
        print(f"--- {label} ---")
        got = {"n": 0}
        try:
            ns = NomadSocket(args.car_ip, args.port, lport, args.bind)
        except OSError as e:
            print(f"    (bind failed: {e})\n")
            continue
        ns.dest = (dest_ip, args.port)
        stop = threading.Event()

        def listener():
            ns.sock.settimeout(0.4)
            while not stop.is_set():
                try:
                    data, addr = ns.sock.recvfrom(4096)
                except (socket.timeout, OSError):
                    continue
                got["n"] += 1
                decode_reply(addr, data)

        th = threading.Thread(target=listener, daemon=True)
        th.start()
        for _ in range(10):
            ns.sock.sendto(b"MAKO_CONNECT", ns.dest)
            time.sleep(0.05)
        ns.sock.sendto(b"MAKO_READ_BATT", ns.dest)
        time.sleep(2.5)
        stop.set()
        ns.close()
        verdict = f"{got['n']} reply(ies)" if got["n"] else "no reply"
        print(f"    => {verdict}\n")
        any_reply = any_reply or got["n"] > 0

    print("=" * 60)
    if any_reply:
        print("RESULT: the car IS answering on at least one configuration above.")
        print("Use the configuration that got replies for the real client.")
    else:
        print("RESULT: no replies on any configuration. Check, in order:")
        print("  1. Is the laptop actually associated to SSID NOMAD_ND1-XXXX?")
        print("  2. Did you get a 192.168.0.x IP?  (ip addr / ifconfig)")
        print("  3. Can you even reach the AP:  ping 192.168.0.1")
        print("  4. Is the car powered on AND in control mode (not firmware/FTP mode)?")
        print("  5. A host firewall may be dropping inbound UDP — try disabling it.")
        print("  6. The car may only wake its control stack after the app's exact")
        print("     handshake timing — try `connect` mode and let it run 30s.")


def mode_drive(ns: NomadSocket, args):
    """Interactive driving REPL. Sends a continuous 10Hz stream like the app."""
    state = {"fw": 0, "bw": 0, "steer": STEER_CENTER, "run": True}
    stop = threading.Event()
    threading.Thread(target=run_listener, args=(ns, stop), daemon=True).start()

    def sender():
        # 10 Hz continuous drive stream — the car expects this cadence.
        while not stop.is_set():
            ns.send_raw(hexstr_to_bytes(drive_hex(state["fw"], state["bw"], state["steer"])))
            time.sleep(0.1)

    print("Connecting...")
    for _ in range(10):
        ns.send_str("MAKO_CONNECT")
        time.sleep(0.05)
    threading.Thread(target=sender, daemon=True).start()

    print("""
Interactive drive. Commands (Enter after each):
  w [pwm]   forward   (default 120)     s [pwm]  reverse
  a [n]     steer left n from center     d [n]    steer right n
  x         all stop / center            q        quit
  1on/1off  upper LED   2on/2off  lower LED
""")
    try:
        while True:
            line = input("drive> ").strip().split()
            if not line:
                state["fw"] = state["bw"] = 0
                state["steer"] = STEER_CENTER
                continue
            c = line[0].lower()
            arg = int(line[1]) if len(line) > 1 and line[1].lstrip("-").isdigit() else None
            if c == "q":
                break
            elif c == "w":
                state["fw"], state["bw"] = (arg or 120), 0
            elif c == "s":
                state["fw"], state["bw"] = 0, (arg or 120)
            elif c == "a":
                state["steer"] = max(0, STEER_CENTER - (arg or 40))
            elif c == "d":
                state["steer"] = min(255, STEER_CENTER + (arg or 40))
            elif c == "x":
                state["fw"] = state["bw"] = 0
                state["steer"] = STEER_CENTER
            elif c == "1on":
                ns.send_str("MAKO_LED1_ON")
            elif c == "1off":
                ns.send_str("MAKO_LED1_OFF")
            elif c == "2on":
                ns.send_str("MAKO_LED2_ON")
            elif c == "2off":
                ns.send_str("MAKO_LED2_OFF")
            else:
                print("  ? unknown")
    except (KeyboardInterrupt, EOFError):
        pass
    finally:
        state["fw"] = state["bw"] = 0
        state["steer"] = STEER_CENTER
        time.sleep(0.2)
        ns.send_str("MAKO_DISCONNECT")
        stop.set()


MODES = {
    "connect": mode_connect,
    "listen": mode_listen,
    "send": mode_send,
    "hex": mode_hex,
    "diagnose": mode_diagnose,
    "drive": mode_drive,
}


def main():
    p = argparse.ArgumentParser(description="Nomad ND1 UDP control probe")
    p.add_argument("mode", choices=MODES.keys(), help="what to do")
    p.add_argument("args", nargs="*", help="extra args for send/hex modes")
    p.add_argument("--car-ip", default=DEFAULT_CAR_IP, dest="car_ip")
    p.add_argument("--port", type=int, default=DEFAULT_PORT)
    p.add_argument("--local-port", type=int, default=DEFAULT_PORT, dest="local_port",
                   help="local bind port (0 = ephemeral like nc; default 8234 = like the app)")
    p.add_argument("--bind", default="0.0.0.0")
    p.add_argument("--timeout", type=float, default=4.0)
    args = p.parse_args()

    if args.mode == "diagnose":
        mode_diagnose(None, args)
        return

    try:
        ns = NomadSocket(args.car_ip, args.port, args.local_port, args.bind)
    except OSError as e:
        print(f"[fatal] could not bind {args.bind}:{args.local_port} -> {e}")
        print("        Another process may hold port 8234, or you lack permission.")
        sys.exit(1)
    try:
        MODES[args.mode](ns, args)
    finally:
        ns.close()


if __name__ == "__main__":
    main()
