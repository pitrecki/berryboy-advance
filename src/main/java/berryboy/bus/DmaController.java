package berryboy.bus;

import berryboy.EmulationException;

/** Four bus-owning DMA channels. Requests latch at device events, CPU services them. */
public final class DmaController {
    private static final int BASE = 0x040000b0;
    private final Memory memory;
    private final Channel[] channels = new Channel[4];

    public DmaController(Memory memory) {
        this.memory = memory;
        for (var i = 0; i < 4; i++) channels[i] = new Channel(i);
    }

    int read8(int address) {
        var offset = address - BASE;
        return (offset % 12) >= 10 ? (channels[offset / 12].control >>> ((offset & 1) * 8)) & 255 : 0;
    }

    void write8(int address, int value) {
        var offset = address - BASE;
        var channel = channels[offset / 12];
        var register = offset % 12;
        var shift = (register & 3) * 8;
        value &= 255;
        if (register < 4) channel.source = replace(channel.source, shift, value);
        else if (register < 8) channel.destination = replace(channel.destination, shift, value);
        else if (register < 10) channel.count = replace(channel.count, (register & 1) * 8, value);
        else {
            var previous = channel.control;
            var control = replace(previous, (register & 1) * 8, value) & 0xffe0;
            if ((control & 0x8000) != 0 && (previous & 0x8000) == 0) {
                if ((control & 0x180) == 0x180) throw new EmulationException("Reserved DMA source mode");
                if ((control & 0x800) != 0) throw new EmulationException("Game Pak DMA DRQ is not implemented");
                if ((control & 0x3000) == 0x3000 && (channel.index == 0 || channel.index == 3)) {
                    throw new EmulationException("Special video/Game Pak DMA timing is not implemented");
                }
            }
            channel.control = control;
            if ((control & 0x8000) == 0) channel.pending = false;
            else if ((previous & 0x8000) == 0) channel.latch();
        }
    }

    public void request(int timing) {
        for (var i = 0; i < 4; i++) {
            var channel = channels[i];
            if ((channel.control & 0x8000) != 0 && ((channel.control >>> 12) & 3) == timing) {
                channel.pending = true;
            }
        }
    }

    void requestFifo(int address) {
        for (var i = 1; i <= 2; i++) {
            var channel = channels[i];
            if ((channel.control & 0xb000) == 0xb000 && (channel.currentDestination & ~3) == address) {
                channel.pending = true;
            }
        }
    }

    /**
     * Service one bus transfer before the CPU can execute another instruction.
     * Yield between units so callers can observe frame/audio deadlines during a
     * long DMA block; pending channels retain ownership and priority on resumption.
     */
    public boolean runPending() {
        for (var i = 0; i < 4; i++) {
            if (channels[i].pending) {
                channels[i].transfer();
                return true;
            }
        }
        return false;
    }

    private static int replace(int current, int shift, int value) {
        return (current & ~(255 << shift)) | (value << shift);
    }

    private final class Channel {
        private final int index;
        private int source;
        private int destination;
        private int count;
        private int control;
        private int currentSource;
        private int currentDestination;
        private int remaining;
        private int initialCount;
        private boolean pending;
        private boolean first = true;

        Channel(int index) { this.index = index; }

        void latch() {
            currentSource = source & (index == 0 ? 0x07ffffff : 0x0fffffff);
            currentDestination = destination & (index == 3 ? 0x0fffffff : 0x07ffffff);
            initialCount = count & (index == 3 ? 0xffff : 0x3fff);
            if (initialCount == 0) initialCount = index == 3 ? 0x10000 : 0x4000;
            if ((control & 0x3000) == 0x3000) initialCount = 4;
            remaining = initialCount;
            memory.prepareEepromDma(currentDestination, initialCount);
            first = true;
            pending = ((control >>> 12) & 3) == 0;
        }

        void transfer() {
            var fifo = (control & 0x3000) == 0x3000;
            var width = fifo || (control & 0x400) != 0 ? 4 : 2;
            var sourceMode = (control >>> 7) & 3;
            var destinationMode = (control >>> 5) & 3;
            if (fifo) destinationMode = 2;
            var sourceAddress = currentSource & ~(width - 1);
            var destinationAddress = currentDestination & ~(width - 1);
            if (first) memory.clock().advance(2); // DMA bus ownership startup.
            memory.clock().advance(memory.busTiming().accessCycles(sourceAddress, width, !first && sourceMode == 0));
            var value = width == 4 ? memory.read32(sourceAddress) : memory.read16(sourceAddress);
            memory.clock().advance(memory.busTiming().accessCycles(destinationAddress, width, !first && destinationMode != 1 && destinationMode != 2));
            if (width == 4) memory.write32(destinationAddress, value);
            else memory.write16(destinationAddress, value);
            currentSource += sourceMode == 0 ? width : sourceMode == 1 ? -width : 0;
            currentDestination += destinationMode == 0 || destinationMode == 3 ? width : destinationMode == 1 ? -width : 0;
            first = false;
            if (--remaining == 0) {
                pending = false;
                if ((control & 0x4000) != 0) memory.interrupts().request(0x100 << index);
                if ((control & 0x200) != 0 && (control & 0x3000) != 0) {
                    remaining = initialCount;
                    first = true;
                    if (destinationMode == 3) currentDestination = destination & (index == 3 ? 0x0fffffff : 0x07ffffff);
                } else control &= ~0x8000;
            }
            memory.busTiming().invalidateFetch();
        }
    }
}
