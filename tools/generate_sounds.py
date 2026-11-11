"""Synthesises the game's short sound effects as 16-bit mono WAV files (original, no samples).

    python tools/generate_sounds.py

Writes place.wav, victory.wav and defeat.wav into src/main/resources/audio. Deterministic.
"""
import math
import os
import random
import struct
import wave

RATE = 22050
OUT = os.path.join(os.path.dirname(__file__), "..", "src", "main", "resources", "audio")


def write(name, samples):
    peak = max(1e-9, max(abs(s) for s in samples))
    with wave.open(os.path.join(OUT, name), "wb") as w:
        w.setnchannels(1)
        w.setsampwidth(2)
        w.setframerate(RATE)
        w.writeframes(b"".join(struct.pack("<h", int(32767 * 0.85 * s / peak)) for s in samples))


def tone(freq, seconds, attack=0.005, decay=6.0, harmonics=((1, 1.0), (2, 0.35), (3, 0.12))):
    out = []
    for i in range(int(RATE * seconds)):
        t = i / RATE
        env = min(1.0, t / attack) * math.exp(-decay * t)
        out.append(env * sum(a * math.sin(2 * math.pi * freq * k * t) for k, a in harmonics))
    return out


def mix(*parts):
    """parts: (offset_seconds, samples)"""
    n = max(int(o * RATE) + len(s) for o, s in parts)
    out = [0.0] * n
    for o, s in parts:
        start = int(o * RATE)
        for i, v in enumerate(s):
            out[start + i] += v
    return out


def place():
    # A short wooden "tock": a damped low tone plus a burst of filtered noise.
    rnd = random.Random(7)
    body = tone(310, 0.12, attack=0.001, decay=38, harmonics=((1, 1.0), (2.7, 0.4)))
    noise, last = [], 0.0
    for i in range(int(RATE * 0.03)):
        last = 0.6 * last + 0.4 * rnd.uniform(-1, 1)
        noise.append(last * math.exp(-i / (RATE * 0.006)))
    return mix((0, body), (0, [0.5 * v for v in noise]))


def victory():
    # Rising major arpeggio ending on a held chord.
    notes = [(0.00, 392.0), (0.14, 493.9), (0.28, 587.3)]
    parts = [(t, tone(f, 0.5, decay=4)) for t, f in notes]
    parts += [(0.44, tone(f, 1.3, decay=1.8)) for f in (523.3, 659.3, 784.0)]
    return mix(*parts)


def defeat():
    # Slow falling minor phrase.
    notes = [(0.0, 392.0, 0.6), (0.36, 349.2, 0.6), (0.72, 311.1, 1.4)]
    return mix(*[(t, tone(f, d, attack=0.02, decay=2.2, harmonics=((1, 1.0), (2, 0.2)))) for t, f, d in notes])


if __name__ == "__main__":
    write("place.wav", place())
    write("victory.wav", victory())
    write("defeat.wav", defeat())
    print("wrote place.wav, victory.wav, defeat.wav")
