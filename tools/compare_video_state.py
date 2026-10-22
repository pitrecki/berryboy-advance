#!/usr/bin/env python3
"""Compare raw BBVID001 snapshots; matching final state does not prove raster timing."""
import argparse
import hashlib
import json
from pathlib import Path
import struct

REGIONS = (("registers", 0x04000000, 0x56), ("palette", 0x05000000, 0x400),
           ("vram", 0x06000000, 0x18000), ("oam", 0x07000000, 0x400))


def decode(data):
    if data[:8] != b"BBVID001":
        raise ValueError("Unknown video-state format")
    offset = 8
    regions = {}
    for name, address, length in REGIONS:
        if len(data) < offset + 8:
            raise ValueError("Truncated video-state header")
        if struct.unpack_from("<II", data, offset) != (address, length):
            raise ValueError("Unexpected video-state region")
        offset += 8
        regions[name] = data[offset:offset + length]
        offset += length
        if len(data) < offset:
            raise ValueError("Truncated video-state region")
    if offset != len(data):
        raise ValueError("Trailing video-state data")
    return regions


def compare(actual, reference):
    left, right = decode(actual), decode(reference)
    result = []
    for name, address, length in REGIONS:
        different = [i for i in range(length) if left[name][i] != right[name][i]]
        result.append(dict(region=name, bytes=length, different_bytes=len(different),
                           first_different_address=hex(address + different[0]) if different else None,
                           actual_sha256=hashlib.sha256(left[name]).hexdigest(),
                           reference_sha256=hashlib.sha256(right[name]).hexdigest()))
    return dict(status="identical" if actual == reference else "different",
                actual_sha256=hashlib.sha256(actual).hexdigest(),
                reference_sha256=hashlib.sha256(reference).hexdigest(), regions=result,
                scope="End-of-capture state only; register history, CPU state and raster timing are not compared")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("actual", type=Path)
    parser.add_argument("reference", type=Path)
    parser.add_argument("--output", type=Path)
    args = parser.parse_args()
    result = compare(args.actual.read_bytes(), args.reference.read_bytes())
    if args.output:
        with args.output.open("x") as output:
            output.write(json.dumps(result, indent=2) + "\n")
    print(json.dumps(result))
    return 0 if result["status"] == "identical" else 1


if __name__ == "__main__":
    raise SystemExit(main())
