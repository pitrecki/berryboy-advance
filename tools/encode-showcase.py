#!/usr/bin/env python3
"""Encode captured production GUI frames as a compact animated GIF."""
import hashlib
import json
from pathlib import Path
import sys
from PIL import Image

source, destination = map(Path, sys.argv[1:])
report = json.loads((source / "capture.json").read_text())
files = sorted(source.glob("*.png"))
if len(files) != report["frames"] or not files:
    raise ValueError("Incomplete showcase capture")
frames = [Image.open(path).convert("RGB") for path in files]
destination.parent.mkdir(parents=True, exist_ok=True)
frames[0].save(destination, save_all=True, append_images=frames[1:], duration=100, loop=0, optimize=True, disposal=1)
with Image.open(destination) as result:
    duration = 0
    for frame in range(result.n_frames):
        result.seek(frame)
        duration += result.info["duration"]
    report["gif"] = {"bytes": destination.stat().st_size, "sha256": hashlib.sha256(destination.read_bytes()).hexdigest(),
                     "size": list(result.size), "encoded_frames": result.n_frames, "duration_ms": duration}
destination.with_suffix(".json").write_text(json.dumps(report, indent=2) + "\n")
print(f"GIF: {destination}, {destination.stat().st_size:,} bytes, {duration / 1000:g} seconds")
