package berryboy.cpu;

/** Internal ARM7TDMI cycles in addition to instruction and data bus transfers. */
final class InstructionTiming {
    private InstructionTiming() {
    }

    static int internal(int opcode, boolean thumb, CpuState state) {
        if (thumb) {
            if ((opcode & 0xfc00) == 0x4000) {
                var operation = (opcode >>> 6) & 15;
                if (operation == 13) return multiplyCycles(state.register((opcode >>> 3) & 7), true);
                if (operation == 2 || operation == 3 || operation == 4 || operation == 7) return 1;
            }
            if ((opcode & 0xf800) == 0x4800) return 1;
            if ((opcode & 0xf000) == 0x5000) return ((opcode >>> 9) & 7) >= 3 ? 1 : 0;
            if ((opcode & 0xe000) == 0x6000 || (opcode & 0xf000) == 0x8000
                    || (opcode & 0xf000) == 0x9000 || (opcode & 0xf600) == 0xb400
                    || (opcode & 0xf000) == 0xc000) return (opcode & 0x800) != 0 ? 1 : 0;
            return 0;
        }
        if ((opcode & 0x0ffffff0) == 0x012fff10) return 0; // BX has refill bus cycles only.
        if ((opcode & 0x0f8000f0) == 0x00800090) {
            return multiplyCycles(state.register((opcode >>> 8) & 15), (opcode & (1 << 22)) != 0)
                    + 1 + ((opcode >>> 21) & 1);
        }
        if ((opcode & 0x0fc000f0) == 0x00000090) {
            return multiplyCycles(state.register((opcode >>> 8) & 15), true) + ((opcode >>> 21) & 1);
        }
        if ((opcode & 0x0fb00ff0) == 0x01000090) return 1;
        if ((opcode & 0x0e000090) == 0x00000090 || (opcode & 0x0c000000) == 0x04000000
                || (opcode & 0x0e000000) == 0x08000000) return (opcode >>> 20) & 1;
        if ((opcode & 0x0e000010) == 0x00000010 && (opcode & 0x80) == 0) return 1;
        return 0;
    }

    private static int multiplyCycles(int multiplier, boolean signed) {
        for (var bytes = 1; bytes < 4; bytes++) {
            var upper = multiplier >> (bytes * 8);
            if (upper == 0 || (signed && upper == -1)) return bytes;
        }
        return 4;
    }
}
