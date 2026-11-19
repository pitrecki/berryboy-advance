#!/usr/bin/env python3
"""Prepare a cold M2 checkpoint and a hash-verified paced profile configuration.

Accepts the project's simple KEY=VALUE Linux scenario files (no Java escapes).
ROM, BIOS, battery data, states, raw captures and profiles remain outside Git.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import subprocess
import wave


def digest(data):
    return hashlib.sha256(data).hexdigest()


def prepare(args):
    config = args.scenario.resolve()
    props = {}
    for line in config.read_text().splitlines():
        if not line.strip() or line.lstrip().startswith(("#", "!")):
            continue
        if "=" not in line or "\\" in line:
            raise ValueError("Use a simple KEY=VALUE scenario without Java escapes")
        key, value = line.split("=", 1)
        props[key.strip()] = value.strip()

    def asset(key):
        path = (config.parent / props[key]).resolve()
        data = path.read_bytes()
        if digest(data) != props[key + "_sha256"]:
            raise ValueError("Scenario asset hash mismatch: " + key)
        props[key] = str(path)
        return data

    asset("rom")
    asset("bios")
    if "input" in props:
        asset("input")
    controls = asset("frame_input")
    if "initial_save_sha256" in props:
        save = (config.parent / props["save"]).resolve()
        if digest(save.read_bytes()) != props["initial_save_sha256"]:
            raise ValueError("Original battery seed changed")
        props["save"] = str(save)
    capture = (config.parent / props["output"]).resolve()
    reference = json.loads((capture / "report.json").read_text())
    for key in ("rom_sha256", "bios_sha256", "frame_input_sha256"):
        if reference[key] != props[key]:
            raise ValueError("Capture identity mismatch: " + key)
    if not 0 <= args.start < args.start + args.frames <= reference["frames"] or args.frames > 3600:
        raise ValueError("Choose 1..3600 frames inside the accepted capture")
    events = []
    for line in controls.decode().splitlines():
        if line.strip() and not line.lstrip().startswith("#"):
            fields = line.split()
            if len(fields) != 2:
                raise ValueError("Expected frame/key controls")
            events.append((int(fields[0]), int(fields[1], 0)))
    if any(a[0] >= b[0] for a, b in zip(events, events[1:])):
        raise ValueError("Frame controls must be ordered")

    chain = hashlib.sha256()
    skipped = count = selected = 0
    for line in (capture / "frames.jsonl").read_text().splitlines():
        frame = json.loads(line)
        if frame["frame"] <= args.start:
            skipped += frame["audio_frames"]
        elif frame["frame"] <= args.start + args.frames:
            chain.update(bytes.fromhex(frame["rgb_sha256"]))
            count += frame["audio_frames"]
            selected += 1
    if selected != args.frames:
        raise ValueError("Reference capture has missing window frames")
    with wave.open(str(capture / "audio.wav"), "rb") as audio:
        if (audio.getnchannels(), audio.getsampwidth(), audio.getframerate()) != (2, 2, 32768):
            raise ValueError("Expected stereo PCM16 at 32768 Hz")
        audio.setpos(skipped)
        pcm = audio.readframes(count)
    if len(pcm) != count * 4:
        raise ValueError("Reference PCM is truncated")

    output = args.output.resolve()
    output.mkdir()  # Fresh folder only; never overwrite a prior checkpoint or profile.
    for name, values in (("prefix", [v for v in events if v[0] < args.start]),
                         ("window", [v for v in events if args.start <= v[0] < args.start + args.frames])):
        (output / (name + ".tsv")).write_text("# after_guest_frame keys\n" + "".join(f"{f}\t{k}\n" for f, k in values))
    props["frame_input"] = str(output / "prefix.tsv")
    props["frame_input_sha256"] = digest((output / "prefix.tsv").read_bytes())
    prepared_config = output / "prepare.properties"
    prepared_config.write_text("".join(f"{k}={v}\n" for k, v in props.items()))
    classes = args.classes.resolve()
    tools = output / "tools"
    tools.mkdir()
    java_home = Path(os.environ["JAVA_HOME"]) / "bin" if os.environ.get("JAVA_HOME") else None
    java = str(java_home / "java") if java_home else "java"
    javac = str(java_home / "javac") if java_home else "javac"
    root = Path(__file__).resolve().parent.parent
    subprocess.run([javac, "--release", "21", "-cp", str(classes), "-d", str(tools),
                    str(root / "tools/StateRoundTrip.java")], check=True)
    subprocess.run([java, "-Djava.awt.headless=true", "-cp", str(classes) + os.pathsep + str(tools),
                    "berryboy.desktop.StateRoundTrip", str(prepared_config), str(args.start), "120",
                    str(output / "prepared")], check=True)
    profile = {key: props[key] for key in ("rom", "rom_sha256", "bios", "bios_sha256")}
    profile.update(state=str(output / "prepared/state.bbs"),
                   state_sha256=digest((output / "prepared/state.bbs").read_bytes()),
                   frame_input=str(output / "window.tsv"), frame_input_sha256=digest((output / "window.tsv").read_bytes()),
                   start_frame=args.start, frames=args.frames, warmups=2, repetitions=3, jfr="true",
                   expected_rgb_chain_sha256=chain.hexdigest(), expected_pcm_sha256=digest(pcm),
                   source_commit=subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=root, text=True).strip(),
                   source_dirty=str(bool(subprocess.check_output(["git", "status", "--porcelain"], cwd=root))).lower(),
                   hardware=args.hardware, output=str(output / "profile"))
    (output / "scene.properties").write_text("".join(f"{k}={v}\n" for k, v in profile.items()))
    print("Prepared:", output / "scene.properties")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("scenario", type=Path)
    parser.add_argument("start", type=int, help="Completed guest frame at the start of the window")
    parser.add_argument("frames", type=int)
    parser.add_argument("output", type=Path)
    parser.add_argument("--classes", type=Path, default=Path("target/classes"))
    parser.add_argument("--hardware", required=True)
    prepare(parser.parse_args())
