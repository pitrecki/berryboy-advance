#!/usr/bin/env python3
"""Capture a bounded mGBA reference stream and compare existing BerryBoy traces."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil
import struct
import subprocess
import wave

from PIL import Image

HEADER = struct.Struct("<IQIII")
RGB_BYTES = 240 * 160 * 3


def stream_format(stream):
    magic = read_exact(stream, 8)
    if magic == b"BBREF001":
        return dict(protocol="BBREF001", reference_pixel_format="RGB888", reference_bytes_per_pixel=4,
                    frame_pixel_format="RGB888", video_color_effects="mGBA RGB888 software arithmetic")
    if magic != b"BBREF002":
        raise ValueError("Unknown reference stream format")
    mode, size = struct.unpack("<II", read_exact(stream, 8))
    if (mode, size) not in ((0, 4), (1, 2)):
        raise ValueError("Unknown or inconsistent reference pixel format")
    native = mode == 1
    return dict(protocol="BBREF002", reference_pixel_format="RGB555" if native else "RGB888",
                reference_bytes_per_pixel=size, frame_pixel_format="RGB888",
                video_color_effects="mGBA RGB555 software arithmetic" if native else "mGBA RGB888 software arithmetic")


def read_exact(stream, count):
    data = bytearray()
    while len(data) < count:
        chunk = stream.read(count - len(data))
        if not chunk:
            raise ValueError(f"Truncated reference stream: expected {count}, received {len(data)} bytes")
        data.extend(chunk)
    return bytes(data)


def capture(binary, properties, output, frames, initial_save=None, initial_save_sha256=None, state_frames=(), event_trace=None):
    settings = dict(line.split("=", 1) for line in properties.read_text().splitlines()
                    if "=" in line and not line.startswith("#"))
    if int(settings.get("devices", "0")) not in (0, 1) or settings.get("rtc", "2000-01-01T00:00:00") != "2000-01-01T00:00:00":
        raise ValueError("This reference adapter currently supports default devices/RTC settings only")
    assets = {}
    for key in ("rom", "bios", "input"):
        path = (properties.parent / settings[key]).resolve()
        digest = hashlib.sha256(path.read_bytes()).hexdigest()
        if digest != settings[key + "_sha256"]:
            raise ValueError(f"Wrong SHA-256 for {key}")
        assets[key] = path
    if initial_save:
        if not initial_save_sha256 or hashlib.sha256(initial_save.read_bytes()).hexdigest() != initial_save_sha256:
            raise ValueError("Wrong or missing initial-save SHA-256")
    elif "save" in settings and (properties.parent / settings["save"]).exists():
        raise ValueError("Initial-save comparison requires an explicit independent scenario; use a save-free configuration")
    output.mkdir(parents=True, exist_ok=False)
    args = [str(binary), str(assets["rom"]), str(assets["bios"]), str(assets["input"]),
            str(frames), str(output / "reference.sav"), settings.get("devices", "0")]
    if initial_save:
        local_save = output / "initial.sav"
        shutil.copyfile(initial_save, local_save)
        args.append(str(local_save))
    if state_frames or event_trace:
        if not initial_save:
            args.append("-")
        args.append(",".join(map(str, state_frames)) if state_frames else "-")
    if event_trace:
        args.extend([str(output / "events.tsv"), *map(str, event_trace)])
    reference_video = hashlib.sha256()
    reference_audio = hashlib.sha256()
    checkpoints = []
    sample_count = 0
    nonzero_samples = 0
    with (output / "stderr.log").open("wb") as stderr:
        process = subprocess.Popen(args, stdout=subprocess.PIPE, stderr=stderr,
                                   env={**os.environ, "TZ": "UTC"})
        try:
            pixel_format = stream_format(process.stdout)
            with (output / "frames.jsonl").open("w") as trace, wave.open(str(output / "audio.wav"), "wb") as audio:
                audio.setnchannels(2)
                audio.setsampwidth(2)
                audio.setframerate(32768)
                for expected in range(1, frames + 1):
                    frame, cycle, pc, cpsr, samples = HEADER.unpack(read_exact(process.stdout, HEADER.size))
                    if frame != expected or samples > 8192 or cycle < frame * 280896:
                        raise ValueError("Invalid reference frame header")
                    pixels = read_exact(process.stdout, RGB_BYTES)
                    pcm = read_exact(process.stdout, samples * 4)
                    reference_video.update(pixels)
                    reference_audio.update(pcm)
                    sample_count += samples
                    nonzero_samples += sum(1 for (sample,) in struct.iter_unpack("<h", pcm) if sample)
                    audio.writeframesraw(pcm)
                    checkpoint = dict(frame=frame, cycle=cycle, pc=pc, cpsr=cpsr,
                                      rgb_sha256=hashlib.sha256(pixels).hexdigest(), audio_frames=samples)
                    if frame in state_frames:
                        state_path = output / f"video-state-{frame}.bin"
                        (output / f"reference.sav.video-state-{frame}.bin").rename(state_path)
                        state = state_path.read_bytes()
                        if len(state) != 100478 or state[:8] != b"BBVID001":
                            raise ValueError("Invalid video-state snapshot")
                        checkpoint["video_state_sha256"] = hashlib.sha256(state).hexdigest()
                    checkpoints.append(checkpoint)
                    trace.write(json.dumps(checkpoint) + "\n")
                    if frame == 1 or frame % 300 == 0 or frame == frames or frame in state_frames:
                        image = Image.frombytes("RGB", (240, 160), pixels)
                        image.save(output / f"frame-{frame}.png")
                        if frame == 1:
                            image.save(output / "first.png")
                        if frame == frames:
                            image.save(output / "last.png")
            if process.stdout.read(1):
                raise ValueError("Unexpected trailing reference data")
            if process.wait(timeout=10):
                raise RuntimeError(f"Reference process failed: {process.returncode}; see {output / 'stderr.log'}")
        except BaseException:
            process.kill()
            process.wait()
            raise
        finally:
            process.stdout.close()
    report = dict(emulator="mGBA 0.10.5", frames=frames, rom_sha256=settings["rom_sha256"],
                  bios_sha256=settings["bios_sha256"], input_sha256=settings["input_sha256"],
                  video_sha256=reference_video.hexdigest(), audio_sha256=reference_audio.hexdigest(),
                  audio_frames=sample_count, sample_rate=32768, audio_filter="mGBA blip_buf",
                  audio_nonzero_samples=nonzero_samples, master_volume=256, muted=False,
                  binary_sha256=hashlib.sha256(binary.read_bytes()).hexdigest(),
                  **pixel_format, video_checkpoints=list(state_frames),
                  initial_save_sha256=initial_save_sha256,
                  qualification="independent headless capture; physical playback unverified",
                  rtc_seed="2000-01-01T00:00:00", rtc_timezone="UTC", devices=int(settings.get("devices", "0")),
                  sensors="stationary; no recorded peripheral motion")
    if event_trace:
        report["event_trace"] = json.loads((output / "events.tsv.json").read_text())
    (output / "report.json").write_text(json.dumps(report, indent=2) + "\n")
    return report, checkpoints


def compare(reference, checkpoints, berryboy):
    report = json.loads((berryboy / "report.json").read_text())
    for key in ("rom_sha256", "bios_sha256", "input_sha256", "frames", "devices", "rtc_seed"):
        if report[key] != reference[key]:
            raise ValueError(f"Capture identity mismatch: {key}")
    actual = [json.loads(line) for line in (berryboy / "frames.jsonl").read_text().splitlines()]
    if len(actual) != len(checkpoints) or any(a["frame"] != r["frame"] for a, r in zip(actual, checkpoints)):
        raise ValueError("Incomplete BerryBoy trace")
    if report["warmup_frames"] != 0:
        raise ValueError("Reference comparison requires the same zero-warmup capture interval")
    if report.get("initial_save_sha256") != reference.get("initial_save_sha256"):
        raise ValueError("Capture identity mismatch: initial save")
    matched = [r["rgb_sha256"] == a["rgb_sha256"] for r, a in zip(checkpoints, actual)]
    first_mismatch = next((i + 1 for i, same in enumerate(matched) if not same), None)
    return dict(status="exact-match" if all(matched) and reference["audio_sha256"] == report["audio_sha256"] else "mismatch",
                exact_video_frames=sum(matched), total_video_frames=len(matched), first_video_mismatch=first_mismatch,
                whole_video_hash_match=reference["video_sha256"] == report["video_sha256"],
                whole_audio_hash_match=reference["audio_sha256"] == report["audio_sha256"],
                reference_audio_frames=reference["audio_frames"], berryboy_audio_frames=report["audio_frames"],
                note="Exact hashes expose differences; mGBA filtering and guest timing can both affect audio. No perceptual tolerance is applied.")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--binary", type=Path, required=True)
    parser.add_argument("--scenario", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--frames", type=int, required=True)
    parser.add_argument("--compare", type=Path)
    parser.add_argument("--initial-save", type=Path)
    parser.add_argument("--initial-save-sha256")
    parser.add_argument("--checkpoints", default="", help="Comma-separated guest-frame IDs for PNG/raw video state")
    parser.add_argument("--event-trace", action="store_true", help="Capture bounded LCD callback snapshots")
    parser.add_argument("--trace-start-cycle", type=int, default=0)
    parser.add_argument("--trace-end-cycle", type=int)
    parser.add_argument("--trace-capacity", type=int, default=65536)
    args = parser.parse_args()
    if not 1 <= args.frames <= 7000:
        parser.error("frames must be 1..7000")
    if bool(args.initial_save) != bool(args.initial_save_sha256):
        parser.error("initial-save and initial-save-sha256 must be supplied together")
    try:
        state_frames = tuple(int(value) for value in args.checkpoints.split(",")) if args.checkpoints else ()
    except ValueError:
        parser.error("checkpoints must be comma-separated frame IDs")
    if len(set(state_frames)) != len(state_frames) or any(frame < 1 or frame > args.frames for frame in state_frames):
        parser.error("checkpoints must be unique and inside captured frames")
    end_cycle = args.trace_end_cycle if args.trace_end_cycle is not None else args.frames * 280896 + 1
    if not (0 <= args.trace_start_cycle < end_cycle <= args.frames * 280896 + 1
            and 1 <= args.trace_capacity <= 1000000):
        parser.error("invalid event trace window or capacity")
    if not args.event_trace and (args.trace_start_cycle or args.trace_end_cycle is not None or args.trace_capacity != 65536):
        parser.error("trace settings require --event-trace")
    event_trace = (args.trace_start_cycle, end_cycle, args.trace_capacity) if args.event_trace else None
    report, checkpoints = capture(args.binary.resolve(), args.scenario.resolve(), args.output.resolve(), args.frames,
                                  args.initial_save.resolve() if args.initial_save else None, args.initial_save_sha256,
                                  state_frames, event_trace)
    if args.compare:
        result = compare(report, checkpoints, args.compare.resolve())
        (args.output / "comparison.json").write_text(json.dumps(result, indent=2) + "\n")
        print(json.dumps(result))
        return 0 if result["status"] == "exact-match" else 1
    print(json.dumps(report))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
