"""Read-only activity fields for the non-battle LeafGreen save routes.

Layout/command source: pret/pokefirered ce6406d8c2eaf46d543c8a2606de0c3cb1951982,
include/{global.h,global.fieldmap.h,quest_log.h}, src/quest_log_events.c.
Unknown commands fail explicitly; this is not a complete Quest Log decoder.
"""
import struct

OBJECT_BASE = 0x6A0
OBJECT_SIZE = 0x24
OBJECT_COUNT = 16
MAP_OFFSET = 7
SCENE_BASE = 0x1300
SCENE_SIZE = 0x668
SCENE_COUNT = 4
SCRIPT_OFFSET = 0x568
SCRIPT_BYTES = 256
ACTION_KINDS = {0: "input", 1: "graphics_change", 2: "movement"}


def coordinates(data, offset):
    x, y = struct.unpack_from("<hh", data, offset)
    return dict(x=x, y=y)


def scene_commands(world, start):
    commands = []
    offset, end = start + SCRIPT_OFFSET, start + SCRIPT_OFFSET + SCRIPT_BYTES
    while offset < end:
        header = struct.unpack_from("<H", world, offset)[0]
        if header not in (0, 1, 2, 35, 39, 41, 42):
            raise ValueError(f"Unsupported Quest Log command 0x{header:04x} at 0x{offset:x}")
        size = 8 if header in ACTION_KINDS else 2 if header == 39 else 4 if header == 41 else 6
        if offset + size > end:
            raise ValueError(f"Truncated Quest Log command at 0x{offset:x}")
        row = dict(world_offset=offset, command_id=header)
        if header in ACTION_KINDS:
            row.update(kind=ACTION_KINDS[header], duration=struct.unpack_from("<H", world, offset + 2)[0])
            if header == 0:
                row["field_input"] = list(world[offset + 4:offset + 8])
            else:
                local_id, map_number, map_group, action = world[offset + 4:offset + 8]
                row.update(local_id=local_id, map_number=map_number, map_group=map_group, action=action)
        elif header == 39:
            row["kind"] = "scene_end"
        elif header == 41:
            row.update(kind="wait", duration=struct.unpack_from("<H", world, offset + 2)[0])
        else:
            row.update(kind="departed" if header == 35 else "arrived",
                       action_index=struct.unpack_from("<H", world, offset + 2)[0],
                       event_data=list(world[offset + 4:offset + 6]))
        commands.append(row)
        if header == 39:
            return commands
        offset += size
    raise ValueError("Missing Quest Log scene end within the 256-byte script")


def inspect_activity(parts):
    # SaveBlock1 spans four logical sections, independently of physical sector rotation.
    world = b"".join(parts[section][1][:0xF80 if section < 4 else 0xEE8] for section in range(1, 5))
    objects, scenes = [], []
    for index in range(OBJECT_COUNT):
        offset = OBJECT_BASE + index * OBJECT_SIZE
        if not world[offset] & 1:
            continue
        current = coordinates(world, offset + 0x10)
        objects.append(dict(slot=index, local_id=world[offset + 8], map_number=world[offset + 9],
                            map_group=world[offset + 10], graphics_id=world[offset + 5],
                            movement_type=world[offset + 6], initial_grid=coordinates(world, offset + 0xC), current_grid=current,
                            current_map=dict(x=current["x"] - MAP_OFFSET, y=current["y"] - MAP_OFFSET),
                            previous_grid=coordinates(world, offset + 0x14),
                            facing_direction=world[offset + 0x18] & 15,
                            movement_direction=world[offset + 0x18] >> 4,
                            previous_movement_direction=world[offset + 0x20]))
    for index in range(SCENE_COUNT):
        start = SCENE_BASE + index * SCENE_SIZE
        kind = world[start]
        if kind == 0:
            continue
        if kind not in (1, 2):
            raise ValueError(f"Unsupported Quest Log scene type {kind}")
        initial_objects = []
        for slot in range(OBJECT_COUNT):
            offset = start + 8 + slot * 0x14
            if world[offset] & 1:
                initial_objects.append(dict(slot=slot, local_id=world[offset + 7], map_number=world[offset + 8],
                                            map_group=world[offset + 9], grid=coordinates(world, offset + 0xA)))
        commands = scene_commands(world, start)
        scenes.append(dict(scene_index=index, start_type=kind, map_group=world[start + 1],
                           map_number=world[start + 2], position=coordinates(world, start + 4),
                           initial_objects=initial_objects, command_count=len(commands), commands=commands))
    return dict(objects=objects, quest_scenes=scenes,
                scope="Non-battle activity inspection only; no bytes are normalized, ignored or repaired. "
                      "Different NPC positions and command timelines remain real payload differences.")
