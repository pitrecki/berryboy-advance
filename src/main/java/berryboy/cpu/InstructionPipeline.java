package berryboy.cpu;

import berryboy.bus.Memory;
import berryboy.EmulationException;
import berryboy.state.StateBuffer;

/** Two fetched WRAM opcodes. Stores and DMA cannot replace words already in the CPU. */
final class InstructionPipeline {
    private final Memory memory;
    private int address;
    private int width;
    private int count;
    private int first;
    private int second;

    InstructionPipeline(Memory memory) { this.memory = memory; }

    int fetch(int pc, boolean thumb) {
        if (!wram(pc)) { clear(); return memory.fetch(pc, thumb); }
        var step = thumb ? 2 : 4;
        if (address != pc || width != step || count != 2) {
            // Debugger/development entry and format-1 states have no captured opcodes.
            // Initialize without inventing bus cycles; real branches fill each slot below.
            beginRefill(pc, step); refillNext(); refillNext();
        }
        var opcode = first;
        memory.fetch(pc, thumb); // Preserve the established physical lookahead timing/context.
        first = second;
        second = read(pc + step * 2);
        address += step;
        if (!wram(address)) clear();
        return opcode;
    }

    void beginRefill(int pc, int step) {
        clear();
        if (wram(pc)) { address = pc; width = step; }
    }

    /** Call after the corresponding bus read completes, before another DMA grant. */
    void refillNext() {
        if (width == 0) return;
        if (count >= 2) throw new EmulationException("Opcode pipeline already filled");
        var opcode = read(address + count * width);
        if (count == 0) first = opcode; else second = opcode;
        count++;
    }

    void clear() { address = 0; width = 0; count = 0; first = 0; second = 0; }

    private int read(int pc) {
        var value = memory.peek16(pc);
        return width == 2 ? value : value | (memory.peek16(pc + 2) << 16);
    }

    private static boolean wram(int pc) { return (pc >>> 24) == 2 || (pc >>> 24) == 3; }

    void transferState(StateBuffer state) {
        address = state.value(address); width = state.value(width); count = state.value(count);
        first = state.value(first); second = state.value(second);
        if (state.reading() && (count < 0 || count > 2
                || (width == 0 ? address != 0 || count != 0 || first != 0 || second != 0
                : (width != 2 && width != 4) || !wram(address) || (address & (width - 1)) != 0
                || (count == 0 && first != 0) || (count < 2 && second != 0)
                || (width == 2 && ((first | second) & 0xffff0000) != 0)))) {
            throw new IllegalArgumentException("Invalid opcode pipeline state");
        }
    }
}
