#!/usr/bin/env python3
"""
Synthesize original UI/engine sound effects for the Nomad ND1 app.

All sounds are generated from scratch (no sampled/original-app audio), so the
repo stays free of third-party copyrighted assets and is safe to push to a
public remote. Output: 22.05 kHz, 16-bit mono PCM WAV.
"""
import os
import wave
import math
import numpy as np

SR = 22050
# res/raw sits two levels up from tools/ ; overridable via NOMAD_RAW_DIR.
OUT = os.environ.get(
    "NOMAD_RAW_DIR",
    os.path.join(os.path.dirname(__file__), "..", "app", "src", "main", "res", "raw"),
)


def write_wav(name, samples):
    # samples: float array in [-1, 1]
    s = np.clip(samples, -1.0, 1.0)
    pcm = (s * 32767.0).astype("<i2")
    with wave.open(f"{OUT}/{name}.wav", "wb") as w:
        w.setnchannels(1)
        w.setsampwidth(2)
        w.setframerate(SR)
        w.writeframes(pcm.tobytes())
    print(f"  {name}.wav  {len(samples)/SR*1000:.0f}ms  {len(samples)} smp")


def t(dur):
    return np.linspace(0, dur, int(SR * dur), endpoint=False)


def env_ad(n, attack, release):
    """Attack-decay amplitude envelope over n samples (seconds)."""
    e = np.ones(n)
    a = int(SR * attack)
    r = int(SR * release)
    if a > 0:
        e[:a] = np.linspace(0, 1, a)
    if r > 0:
        e[-r:] *= np.linspace(1, 0, r)
    return e


def blip(freq, dur, attack=0.004, release=None, harmonics=(1.0, 0.25), gain=0.6):
    release = dur * 0.6 if release is None else release
    x = t(dur)
    sig = np.zeros_like(x)
    for i, amp in enumerate(harmonics, start=1):
        sig += amp * np.sin(2 * math.pi * freq * i * x)
    sig *= env_ad(len(x), attack, release)
    return gain * sig / max(abs(sig).max(), 1e-6) * 0.9


def sweep(f0, f1, dur, attack=0.006, release=0.08, gain=0.7):
    x = t(dur)
    # exponential frequency glide
    k = np.linspace(0, 1, len(x))
    freq = f0 * (f1 / f0) ** k
    phase = 2 * math.pi * np.cumsum(freq) / SR
    sig = np.sin(phase) + 0.3 * np.sin(2 * phase)
    sig *= env_ad(len(x), attack, release)
    return gain * sig / max(abs(sig).max(), 1e-6) * 0.9


def click(dur, cutoff_hz, gain=0.7, seed=0):
    rng = np.random.default_rng(seed)
    n = int(SR * dur)
    noise = rng.standard_normal(n)
    # simple one-pole low-pass to tame the hiss
    a = math.exp(-2 * math.pi * cutoff_hz / SR)
    y = np.zeros(n)
    acc = 0.0
    for i in range(n):
        acc = (1 - a) * noise[i] + a * acc
        y[i] = acc
    y *= env_ad(n, 0.0005, dur * 0.8)
    return gain * y / max(abs(y).max(), 1e-6) * 0.9


# --- Engine idle loop: seamless, integer cycles at the loop point -----------
def engine_loop():
    base = 60.0            # fundamental Hz
    cycles = 30            # 30 cycles => 0.5 s exactly at 60 Hz => seamless loop
    dur = cycles / base
    x = t(dur)
    # Sawtooth-ish stack of harmonics (all integer multiples => loop-safe).
    sig = np.zeros_like(x)
    for h, amp in [(1, 1.0), (2, 0.5), (3, 0.33), (4, 0.22), (6, 0.12)]:
        sig += amp * np.sin(2 * math.pi * base * h * x)
    # Slow tremolo at 8 Hz (4 whole cycles over 0.5s => loop-safe).
    trem = 0.85 + 0.15 * np.sin(2 * math.pi * 8 * x)
    sig *= trem
    sig /= max(abs(sig).max(), 1e-6)
    return 0.55 * sig


print("Synthesizing Nomad ND1 sound effects...")
write_wav("eng_loop", engine_loop())
write_wav("sfx_connect", sweep(392, 784, 0.42))                 # rising G4->G5
write_wav("sfx_disconnect", sweep(660, 196, 0.40))              # falling
write_wav("sfx_stop", blip(160, 0.16, harmonics=(1.0, 0.4, 0.2), gain=0.7))
write_wav("sfx_led_on", blip(1180, 0.09, harmonics=(1.0, 0.3)))
write_wav("sfx_led_off", blip(560, 0.09, harmonics=(1.0, 0.3)))
write_wav("sfx_trim_up", blip(1320, 0.06, harmonics=(1.0,)))
write_wav("sfx_trim_dn", blip(880, 0.06, harmonics=(1.0,)))
# Camera shutter: two fast filtered-noise clicks.
sh = np.concatenate([click(0.03, 3500, seed=1), np.zeros(int(SR*0.02)),
                     click(0.04, 2500, seed=2)])
write_wav("sfx_photo", sh)
# Record toggle: a crisp double beep.
rec = np.concatenate([blip(990, 0.08, harmonics=(1.0, 0.2)),
                      np.zeros(int(SR*0.03)),
                      blip(1320, 0.10, harmonics=(1.0, 0.2))])
write_wav("sfx_rec", rec)
print("Done.")
