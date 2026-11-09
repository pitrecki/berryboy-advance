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
# Field layouts and XOR widths: pret/pokefirered ce6406d8c2eaf46d543c8a2606de0c3cb1951982,
# include/global.h and src/{load_save,item,overworld,berry_powder}.c.
BAG_POCKETS = ((0x310, 42), (0x3B8, 30), (0x430, 13), (0x464, 58), (0x54C, 43))


def parse(data):
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
        parts[section] = (sector, block)
        valid_sectors += 1
    if not generations:
        raise ValueError("No valid saved generation")
    if any(set(parts) != set(range(SECTION_COUNT)) for parts in generations.values()):
        raise ValueError("Incomplete saved generation")
    # GetSaveValidStatus in the pinned pret/pokefirered src/save.c chooses zero
    # over UINT32_MAX only for this exact pair. Other pairs use unsigned max;
    # a general serial-number comparison would disagree with the game.
    latest = 0 if 0 in generations and 0xFFFFFFFF in generations else max(generations)
    trainer, world = generations[latest][0][1], generations[latest][1][1]
    hours, minutes, seconds, frames = struct.unpack_from("<HBBB", trainer, PLAY_TIME_OFFSET)
    if minutes >= 60 or seconds >= 60 or frames >= 60:
        raise ValueError("Invalid play-time fields")
    x, y, map_group, map_number = struct.unpack_from("<hhBB", world)
    key = struct.unpack_from("<I", trainer, ENCRYPTION_KEY_OFFSET)[0]
    money = struct.unpack_from("<I", world, MONEY_OFFSET)[0] ^ key
    summary = dict(save_sha256=hashlib.sha256(data).hexdigest(), bytes=len(data),
                valid_sectors=valid_sectors, generations=sorted(generations), latest_generation=latest,
                position=dict(x=x, y=y, map_group=map_group, map_number=map_number),
                play_time=dict(hours=hours, minutes=minutes, seconds=seconds, frames=frames), money=money)
    return summary, generations


def inspect(data):
    return parse(data)[0]


def encrypted_fields():
    # (section, local offset, XOR width); SaveBlock1 spans sections 1 through 4.
    fields = [(0, 0xAF8, 4)]  # Berry powder in SaveBlock2.
    world = [(MONEY_OFFSET, 4), (0x294, 2)]
    for base, count in BAG_POCKETS:
        world.extend((base + item * 4 + 2, 2) for item in range(count))
    world.extend((0x1200 + stat * 4, 4) for stat in range(64))
    world.extend((0x3D3C + tower * 12, 4) for tower in range(4))
    fields.extend((1 + offset // 0xF80, offset % 0xF80, size) for offset, size in world)
    return fields


def decode_sections(parts):
    decoded = {section: bytearray(block[:SECTION_LENGTHS[section]])
               for section, (_, block) in parts.items()}
    key = struct.unpack_from("<I", decoded[0], ENCRYPTION_KEY_OFFSET)[0]
    for section, offset, size in encrypted_fields():
        value = int.from_bytes(decoded[section][offset:offset + size], "little")
        value ^= key & ((1 << (size * 8)) - 1)
        decoded[section][offset:offset + size] = value.to_bytes(size, "little")
    return key, decoded


def compare(left, right):
    left_summary, left_generations = parse(left)
    right_summary, right_generations = parse(right)
    # Classify physical bytes only when generation, section and sector agree.
    # Different layouts, padding, counters and unknown payload changes stay unexplained.
    kinds = ["unexplained"] * FLASH_BYTES
    decoded_differences = []
    for generation in sorted(left_generations.keys() & right_generations.keys()):
        a, b = left_generations[generation], right_generations[generation]
        key_a, decoded_a = decode_sections(a)
        key_b, decoded_b = decode_sections(b)
        for section in range(SECTION_COUNT):
            offsets = [offset for offset, (x, y) in enumerate(zip(decoded_a[section], decoded_b[section]))
                       if x != y]
            if offsets:
                decoded_differences.append(dict(generation=generation, section=section,
                                                different_bytes=len(offsets), offsets=offsets))
            if a[section][0] != b[section][0]:
                continue
            base = a[section][0] * SECTOR_BYTES
            kinds[base + 0xFF6:base + 0xFF8] = ["section_checksum"] * 2
            if section == 0:
                kinds[base + PLAY_TIME_OFFSET:base + PLAY_TIME_OFFSET + 5] = ["play_time"] * 5
                kinds[base + ENCRYPTION_KEY_OFFSET:base + ENCRYPTION_KEY_OFFSET + 4] = ["encryption_key"] * 4
        for section, offset, size in encrypted_fields():
            if (key_a != key_b and a[section][0] == b[section][0]
                    and decoded_a[section][offset:offset + size] == decoded_b[section][offset:offset + size]):
                base = a[section][0] * SECTOR_BYTES + offset
                kinds[base:base + size] = ["rekeyed_encrypted_fields"] * size
    counts = dict.fromkeys(("rekeyed_encrypted_fields", "encryption_key", "play_time",
                            "section_checksum", "unexplained"), 0)
    for index, (x, y) in enumerate(zip(left, right)):
        if x != y:
            counts[kinds[index]] += 1
    return dict(raw_equal=left == right, raw_different_bytes=sum(counts.values()),
                raw_difference_categories=counts, left=left_summary, right=right_summary,
                only_left_generations=sorted(left_generations.keys() - right_generations.keys()),
                only_right_generations=sorted(right_generations.keys() - left_generations.keys()),
                decoded_payload_differences=decoded_differences,
                scope="Read-only diagnosis; keys and play time remain decoded differences. "
                      "Classification never relaxes strict whole-file or RGB/PCM comparison.")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("save", type=Path)
    parser.add_argument("--compare", type=Path, help="Diagnose differences; exit 1 unless raw files match")
    parser.add_argument("--activity", action="store_true", help="Decode active objects and supported non-battle Quest Log commands")
    args = parser.parse_args()
    data = args.save.read_bytes()
    other = args.compare.read_bytes() if args.compare else None
    result = compare(data, other) if args.compare else inspect(data)
    if args.activity:
        from leafgreen_activity import inspect_activity
        images = ((data, result["left"]), (other, result["right"])) if args.compare else ((data, result),)
        for image, summary in images:
            _, generations = parse(image)
            summary["activity"] = inspect_activity(generations[summary["latest_generation"]])
    print(json.dumps(result, indent=2))
    return 1 if args.compare and not result["raw_equal"] else 0


if __name__ == "__main__":
    try:
        sys.exit(main())
    except (OSError, ValueError, struct.error) as error:
        print(f"Save inspection failed: {error}", file=sys.stderr)
        sys.exit(2)
