"""Read-only party and Oak-lab progress for LeafGreen USA Rev-A.

Layout: pret/pokefirered ce6406d8c2eaf46d543c8a2606de0c3cb1951982,
include/{global.h,pokemon.h,constants/{flags,vars}.h}, src/pokemon.c and
data/maps/PalletTown_ProfessorOaksLab/scripts.inc. The lab battle flag is set
after either outcome: it records completion, not proof of a victory.
"""
import struct

PARTY_COUNT = 0x34
PARTY_START = 0x38
MON_BYTES = 100
FLAGS_START = 0xEE0
VARS_START = 0x1000
OAK_BATTLE_FLAG = 0x258
# GetSubstruct(personality % 24, type 0): physical position of the growth data.
GROWTH_POSITIONS = (0, 0, 0, 0, 0, 0,
                    1, 1, 2, 3, 2, 3,
                    1, 1, 2, 3, 2, 3,
                    1, 1, 2, 3, 2, 3)


def inspect_mon(data, slot):
    personality, trainer = struct.unpack_from("<II", data)
    flags = data[19]
    if flags & 1 or not flags & 2:
        raise ValueError(f"Invalid occupied Pokemon in party slot {slot}")
    key = personality ^ trainer
    secure = b"".join(struct.pack("<I", word ^ key)
                      for (word,) in struct.iter_unpack("<I", data[32:80]))
    checksum = sum(word for (word,) in struct.iter_unpack("<H", secure)) & 0xFFFF
    if checksum != struct.unpack_from("<H", data, 28)[0]:
        raise ValueError(f"Pokemon checksum mismatch in party slot {slot}")
    growth = GROWTH_POSITIONS[personality % 24] * 12
    species, = struct.unpack_from("<H", secure, growth)
    experience, = struct.unpack_from("<I", secure, growth + 4)
    level = data[84]
    hp, maximum = struct.unpack_from("<HH", data, 86)
    if species == 0 or not 1 <= level <= 100 or maximum == 0 or hp > maximum:
        raise ValueError(f"Invalid party stats in slot {slot}")
    return dict(slot=slot, species_id=species, experience=experience, level=level,
                hp=hp, max_hp=maximum, is_egg=bool(flags & 4), box_checksum_valid=True)


def inspect_progress(parts):
    world = b"".join(parts[section][1][:0xF80 if section < 4 else 0xEE8]
                     for section in range(1, 5))
    count = world[PARTY_COUNT]
    if count > 6:
        raise ValueError("Invalid party count")
    party = [inspect_mon(world[PARTY_START + slot * MON_BYTES:
                               PARTY_START + (slot + 1) * MON_BYTES], slot)
             for slot in range(count)]
    starter, = struct.unpack_from("<H", world, VARS_START + 2 * 0x31)
    scene, = struct.unpack_from("<H", world, VARS_START + 2 * 0x55)
    completed = bool(world[FLAGS_START + OAK_BATTLE_FLAG // 8] & (1 << (OAK_BATTLE_FLAG % 8)))
    return dict(party_count=count, party=party, starter_choice=starter, oak_lab_scene=scene,
                oak_rival_battle_completed=completed,
                scope="Stored party and lab completion only. The battle flag is set after either outcome; "
                      "victory requires separate gameplay evidence. No save bytes are modified.")
