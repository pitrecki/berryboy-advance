#!/usr/bin/env python3
"""Validate local assets and run deterministic headless game captures."""
import argparse
import datetime
import hashlib
import json
import math
import pathlib
import platform
import shutil
import subprocess
import sys

ROOT = pathlib.Path(__file__).resolve().parent.parent
PATH_FIELDS = {"rom", "bios", "input", "save"}
FIELDS = PATH_FIELDS | {"rom_sha256", "bios_sha256", "input_sha256", "save_sha256",
                       "backup", "devices", "rtc", "reference_video_sha256", "reference_audio_sha256"}


def sha256(path):
    result = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            result.update(block)
    return result.hexdigest()


def properties_value(value):
    return str(value).replace("\\", "\\\\").replace("\n", "\\n").replace("\r", "\\r")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--config", type=pathlib.Path, required=True)
    parser.add_argument("--output", type=pathlib.Path)
    parser.add_argument("--smoke", action="store_true", help="Three-frame diagnostic, one repetition, no warmup")
    args = parser.parse_args()
    config_path = args.config.resolve()
    config = json.loads(config_path.read_text())
    suite = json.loads((ROOT / "benchmarks/suite.json").read_text())
    games = config["games"]
    expected = {game["id"] for game in suite["games"]}
    if not games or (not args.smoke and set(games) != expected):
        raise ValueError("Normal runs require every game in benchmarks/suite.json; no missing-game skips")
    prepared = {}
    for name, settings in games.items():
        if not name or any(c not in "abcdefghijklmnopqrstuvwxyz0123456789-" for c in name):
            raise ValueError("Game identifiers must contain lowercase letters, digits and hyphens")
        if set(settings) - FIELDS:
            raise ValueError(f"Unknown scenario properties for {name}: {sorted(set(settings) - FIELDS)}")
        if "rom" not in settings or "rom_sha256" not in settings:
            raise ValueError(f"{name}: ROM and its SHA-256 are required")
        if not args.smoke and ("input" not in settings or "reference_video_sha256" not in settings
                               or "reference_audio_sha256" not in settings):
            raise ValueError(f"{name}: qualification captures require deterministic inputs and both references")
        resolved = dict(settings)
        for field in PATH_FIELDS & settings.keys():
            path = (config_path.parent / settings[field]).resolve()
            expected_hash = settings.get(field + "_sha256")
            if not expected_hash or sha256(path) != expected_hash:
                raise ValueError(f"{name}: missing/wrong SHA-256 for {field}")
            resolved[field] = str(path)
        prepared[name] = resolved
    stamp = datetime.datetime.now(datetime.timezone.utc).strftime("%Y%m%dT%H%M%S%fZ")
    output = (args.output or ROOT / "target/benchmarks" / stamp).resolve()
    output.mkdir(parents=True, exist_ok=False)
    commit = subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=ROOT, text=True).strip()
    dirty = bool(subprocess.check_output(["git", "status", "--porcelain"], cwd=ROOT, text=True))
    hardware = platform.platform()
    cpuinfo = pathlib.Path("/proc/cpuinfo")
    if cpuinfo.exists():
        for line in cpuinfo.read_text().splitlines():
            if line.startswith("model name"):
                hardware += "; " + line.split(":", 1)[1].strip()
                break
    repetitions = 1 if args.smoke else suite["repetitions"]
    refresh = suite["gba_clock_hz"] / suite["cycles_per_frame"]
    frames = 3 if args.smoke else math.ceil(refresh * suite["measurement_seconds"])
    warmup = 0 if args.smoke else math.ceil(refresh * suite["warmup_seconds"])
    runs = []
    for name, settings in prepared.items():
        for repetition in range(1, repetitions + 1):
            run_dir = output / name / str(repetition)
            run_dir.mkdir(parents=True)
            props = {k: v for k, v in settings.items() if k != "save_sha256"}
            if "save" in props:
                local_save = run_dir / "game.sav"
                shutil.copyfile(props["save"], local_save)
                props["save"] = str(local_save)
            props.update(frames=frames, warmup_frames=warmup, output=str(run_dir / "capture"),
                         commit=commit, source_dirty=str(dirty).lower(), hardware=hardware)
            scenario = run_dir / "scenario.properties"
            scenario.write_text("\n".join(k + "=" + properties_value(v) for k, v in props.items()) + "\n")
            print(f"Capturing {name}, repetition {repetition}/{repetitions}", file=sys.stderr, flush=True)
            with (run_dir / "stdout.log").open("w") as stdout, (run_dir / "stderr.log").open("w") as stderr:
                result = subprocess.run(["java", "-Djava.awt.headless=true", "-cp", str(ROOT / "target/classes"),
                                         "berryboy.desktop.ScenarioRunner", str(scenario)], cwd=ROOT,
                                        stdout=stdout, stderr=stderr)
            report_path = run_dir / "capture/report.json"
            report = json.loads(report_path.read_text()) if result.returncode == 0 and report_path.exists() else None
            runs.append({"game": name, "repetition": repetition, "exit_code": result.returncode,
                         "initial_save_sha256": settings.get("save_sha256"), "report": report})
    success = all(run["exit_code"] == 0 and run["report"] and run["report"]["status"] == "captured" for run in runs)
    summary = {"status": ("diagnostic" if args.smoke else "captured-unqualified") if success else "failed",
               "commit": commit, "source_dirty": dirty, "hardware": hardware,
               "suite_sha256": sha256(ROOT / "benchmarks/suite.json"), "runs": runs,
               "physical_video_audio": "unverified", "bold_9900": "unrun"}
    (output / "summary.json").write_text(json.dumps(summary, indent=2) + "\n")
    print(output / "summary.json")
    return 0 if success else 1


if __name__ == "__main__":
    try:
        sys.exit(main())
    except (ValueError, KeyError, OSError) as error:
        print(f"Benchmark setup failed: {error}", file=sys.stderr)
        sys.exit(2)
