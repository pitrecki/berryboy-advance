#!/usr/bin/env python3
"""Describe level and local timing differences between two stereo PCM16 captures.

Correlation is exploratory, not an audible-quality or A/V qualification gate.
Positive lag means a reference window later in its stream matches BerryBoy.
"""
import argparse
import json
from pathlib import Path
import wave

import numpy as np


def read_wave(path):
    with wave.open(str(path), "rb") as audio:
        if audio.getnchannels() != 2 or audio.getsampwidth() != 2 or audio.getframerate() != 32768:
            raise ValueError("Expected stereo PCM16 at 32768 Hz")
        count = audio.getnframes()
        data = audio.readframes(count)
        if len(data) != count * 4:
            raise ValueError("Truncated WAV payload")
    return np.frombuffer(data, dtype="<i2").reshape(-1, 2).astype(np.float64).mean(axis=1)


def window_comparison(actual, reference, start, length, radius):
    if start < radius or start + length + radius > len(reference) or start + length > len(actual):
        return None
    x = actual[start:start + length]
    y = reference[start - radius:start + length + radius]
    centered = x - x.mean()
    x_energy = float(np.dot(centered, centered))
    result = {"berryboy_rms": float(np.sqrt(np.mean(x * x)))}
    if x_energy < length:
        return {**result, "status": "too-quiet", "correlation": None, "lag_samples": None}
    fft_size = 1 << (len(y) + len(x) - 2).bit_length()
    dot = np.fft.irfft(np.fft.rfft(y, fft_size) * np.fft.rfft(centered[::-1], fft_size), fft_size)
    dot = dot[length - 1:len(y)]
    sums = np.concatenate(([0.0], np.cumsum(y)))
    squares = np.concatenate(([0.0], np.cumsum(y * y)))
    total = sums[length:] - sums[:-length]
    square = squares[length:] - squares[:-length]
    energy = np.maximum(0.0, square - total * total / length)
    valid = energy >= length
    correlation = np.full(len(dot), -np.inf)
    correlation[valid] = dot[valid] / np.sqrt(x_energy * energy[valid])
    if not valid.any():
        return {**result, "status": "reference-too-quiet", "correlation": None, "lag_samples": None}
    best = int(np.argmax(correlation))
    return {**result, "status": "measured", "correlation": float(np.clip(correlation[best], -1, 1)),
            "lag_samples": best - radius, "lag_ms": (best - radius) * 1000 / 32768,
            "reference_rms": float(np.sqrt(square[best] / length))}


def compare(actual_path, reference_path):
    actual, reference = read_wave(actual_path), read_wave(reference_path)
    windows = []
    for second in (1, 10, 20, 30, 40, 50):
        result = window_comparison(actual, reference, second * 32768, 2 * 32768, 32768 // 2)
        if result:
            windows.append({"start_seconds": second, "duration_seconds": 2, **result})
    return {"berryboy_samples": len(actual), "reference_samples": len(reference),
            "sample_delta": len(reference) - len(actual), "search_radius_ms": 500,
            "signal": "mean of left/right; centered normalized local correlation",
            "qualification": "exploratory; no perceptual tolerance, A/V drift or physical playback qualification",
            "windows": windows}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("berryboy", type=Path)
    parser.add_argument("reference", type=Path)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    result = compare(args.berryboy, args.reference)
    with args.output.open("x") as stream:
        stream.write(json.dumps(result, indent=2) + "\n")
    print(json.dumps(result))


if __name__ == "__main__":
    main()
