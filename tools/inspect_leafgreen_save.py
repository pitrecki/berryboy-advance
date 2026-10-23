#!/usr/bin/env python3
"""Read-only LeafGreen USA Rev-A Flash inspection for the recorded save routes."""
import argparse
import hashlib
import json
import struct
import sys
from pathlib import Path

FLASH_BYTES = 0x20000
SECTOR_BYTES = 0x1000
SAVE_SECTORS = 28
SECTION_COUNT = 14
TRAILER_OFFSET = 0xFF4
SAVE_SIGNATURE = 0x08012025
SECTION_LENGTHS = (0xF24, 0xF80, 0xF80, 0xF80, 0xEE8) + (0xF80,) * 8 + (0x7D0,)
PLAY_TIME_OFFSET = 0xE
ENCRYPTION_KEY_OFFSET = 0xF20
MONEY_OFFSET = 0x290


def inspect(data):
    if len(data) != FLASH_BYTES:
        raise ValueError("Expected a 128 KiB LeafGreen Flash image")
    generations = {}
    valid_sectors = 0
    for sector in range(SAVE_SECTORS):
        block = data[sector * SECTOR_BYTES:(sector + 1) * SECTOR_BYTES]
        if block == b"\xff" * SECTOR_BYTES or block == bytes(SECTOR_BYTES):
            continue
        section, stored_checksum, signature, counter = struct.unpack_from("<HHII", block, TRAILER_OFFSET)
        if signature != SAVE_SIGNATURE or section >= SECTION_COUNT:
            raise ValueError(f"Invalid section identity in sector {sector}")
        words = struct.iter_unpack("<I", block[:SECTION_LENGTHS[section]])
        total = sum(word[0] for word in words) & 0xFFFFFFFF
        checksum = ((total >> 16) + (total & 0xFFFF)) & 0xFFFF
        if checksum != stored_checksum:
            raise ValueError(f"Checksum mismatch in sector {sector}, section {section}")
        parts = generations.setdefault(counter, {})
        if section in parts:
            raise ValueError(f"Duplicate section {section} in generation {counter}")
        parts[section] = block
        valid_sectors += 1
    if not generations:
        raise ValueError("No valid saved generation")
    if 0 in generations and 0xFFFFFFFF in generations:
        raise ValueError("Save-counter wrap is outside this diagnostic's scope")
    if any(set(parts) != set(range(SECTION_COUNT)) for parts in generations.values()):
        raise ValueError("Incomplete saved generation")
    latest = max(generations)
    trainer, world = generations[latest][0], generations[latest][1]
    hours, minutes, seconds, frames = struct.unpack_from("<HBBB", trainer, PLAY_TIME_OFFSET)
    if minutes >= 60 or seconds >= 60 or frames >= 60:
        raise ValueError("Invalid play-time fields")
    x, y, map_group, map_number = struct.unpack_from("<hhBB", world)
    key = struct.unpack_from("<I", trainer, ENCRYPTION_KEY_OFFSET)[0]
    money = struct.unpack_from("<I", world, MONEY_OFFSET)[0] ^ key
    return dict(save_sha256=hashlib.sha256(data).hexdigest(), bytes=len(data),
                valid_sectors=valid_sectors, generations=sorted(generations), latest_generation=latest,
                position=dict(x=x, y=y, map_group=map_group, map_number=map_number),
                play_time=dict(hours=hours, minutes=minutes, seconds=seconds, frames=frames), money=money)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("save", type=Path)
    args = parser.parse_args()
    print(json.dumps(inspect(args.save.read_bytes()), indent=2))


if __name__ == "__main__":
    try:
        main()
    except (OSError, ValueError, struct.error) as error:
        print(f"Save inspection failed: {error}", file=sys.stderr)
        sys.exit(2)
