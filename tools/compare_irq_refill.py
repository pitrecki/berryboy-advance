#!/usr/bin/env python3
"""Compare full IRQ traces from the handler retirement, retaining reference disagreements."""
import argparse
import csv
import hashlib
import io
import json
from pathlib import Path
import sys

HEADER = ["mode", "code", "prefetch", "idle", "stage", "cycle", "pc", "cpsr", "lr", "r0"]
STATE = ("pc", "cpsr", "lr", "r0")
FIXTURE_HEADER = ["mode", "code", "prefetch", "idle", "stage", "cycles_since_handler", *STATE]


def load(path):
    data = path.read_bytes()
    reader = csv.DictReader(io.StringIO(data.decode("utf-8")))
    if reader.fieldnames != HEADER:
        raise ValueError(f"Wrong trace header: {path}")
    cases = {}
    for number, row in enumerate(reader, 2):
        if None in row or any(value is None for value in row.values()):
            raise ValueError(f"Incomplete row {number}: {path}")
        values = {name: int(row[name], 0) for name in HEADER if name != "mode"}
        width = 2 if row["mode"] == "thumb" else 4
        if (row["mode"] not in ("arm", "thumb") or values["prefetch"] not in (0, 1)
                or values["idle"] < 0 or values["stage"] not in range(1, 6)
                or not 0 <= values["cycle"] <= 0x7FFFFFFFFFFFFFFF
                or any(not 0 <= values[name] <= 0xFFFFFFFF for name in ("code", *STATE))
                or values["code"] % width):
            raise ValueError(f"Invalid trace value at row {number}: {path}")
        key = (row["mode"], values["code"], values["prefetch"], values["idle"])
        stages = cases.setdefault(key, {})
        if values["stage"] in stages:
            raise ValueError(f"Duplicate stage at row {number}: {path}")
        stages[values["stage"]] = values
    if not cases:
        raise ValueError(f"Empty trace: {path}")
    for stages in cases.values():
        if set(stages) != set(range(1, 6)):
            raise ValueError(f"Incomplete five-stage scenario: {path}")
        if any(stages[i]["cycle"] >= stages[i + 1]["cycle"] for i in range(1, 5)):
            raise ValueError(f"Non-increasing cycle count: {path}")
    return cases, hashlib.sha256(data).hexdigest()


def observation(row, anchor):
    return {"cycles_since_handler": row["cycle"] - anchor["cycle"],
            **{name: row[name] for name in STATE}}


def compare(nba, mgba, host):
    if set(nba) != set(mgba) or set(nba) != set(host):
        raise ValueError("Scenario keys differ between traces")
    shared, disputed, mismatches = [], [], []
    for key in sorted(nba):
        anchors = [trace[key][1] for trace in (nba, mgba, host)]
        if any(tuple(anchor[name] for name in STATE) != tuple(anchors[0][name] for name in STATE)
               for anchor in anchors[1:]):
            raise ValueError("Cannot align different handler retirement states")
        for stage in range(2, 6):
            expected, other, actual = [observation(trace[key][stage], anchor)
                                      for trace, anchor in zip((nba, mgba, host), anchors)]
            identity = dict(zip(("mode", "code", "prefetch", "idle"), key), stage=stage)
            if expected != other:
                disputed.append(dict(**identity, nba=expected, mgba=other, host=actual))
            else:
                shared.append(dict(**identity, **expected))
                if actual != expected:
                    mismatches.append(dict(**identity, expected=expected, host=actual))
    qualified = not disputed and not mismatches
    report = dict(status="exact-match" if qualified else "unqualified",
                  scenarios=len(nba), phase_observations=len(nba) * 4,
                  shared_reference_observations=len(shared),
                  shared_subset_exact=bool(shared) and not mismatches,
                  full_phase_qualified=qualified,
                  host_shared_mismatch_count=len(mismatches), model_disagreement_count=len(disputed),
                  host_shared_mismatches=mismatches, model_disagreements=disputed,
                  scope="Cycle origin is the retired handler NOP. Shared fixtures contain only complete "
                        "NBA/mGBA agreements on cycles, PC, CPSR, LR and r0. No tolerance or host-based selection. "
                        "Absolute IRQ assertion/entry latency is outside this comparison.")
    return report, shared


def fixture(path, shared):
    if not shared:
        raise ValueError("Cannot publish an empty shared fixture")
    # Refuse stale output; leave the input traces untouched.
    with path.open("x", newline="") as output:
        writer = csv.DictWriter(output, FIXTURE_HEADER, lineterminator="\n")
        writer.writeheader()
        for row in shared:
            value = dict(row)
            for name in ("code", "pc", "cpsr", "lr"):
                value[name] = f"0x{value[name]:08x}"
            writer.writerow(value)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("nba", type=Path)
    parser.add_argument("mgba", type=Path)
    parser.add_argument("host", type=Path)
    parser.add_argument("--shared-fixture", type=Path)
    args = parser.parse_args()
    try:
        loaded = [load(path) for path in (args.nba, args.mgba, args.host)]
        report, shared = compare(*(cases for cases, _ in loaded))
        report["input_sha256"] = dict(zip(("nba", "mgba", "host"), (digest for _, digest in loaded)))
        if args.shared_fixture:
            fixture(args.shared_fixture, shared)
    except (OSError, UnicodeError, ValueError) as error:
        parser.error(str(error))
    print(json.dumps(report, indent=2))
    # A matching shared subset never turns unresolved reference differences into a full pass.
    return 0 if report["full_phase_qualified"] else 1


if __name__ == "__main__":
    sys.exit(main())
