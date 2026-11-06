/* Original asset-free activation/cancellation observations using unmodified NBA.
 * No CPU or transfer is serviced: inspect readiness at exact cycle boundaries.
 */
#include "nba-reference/probe-machine.hh"

int main() {
    std::puts("timing,channel,operation,ready_mask,enabled");
    for (unsigned timing : {0, 1, 2, 3})
        for (unsigned channel : {0, 1, 2, 3}) {
            if (timing == 3 && (channel == 0 || channel == 3)) continue;
            for (unsigned operation : {0, 1, 2, 3, 4}) {
                if (timing == 0 && operation == 4) continue;
                auto m = std::make_unique<ProbeMachine>();
                const u32 control = timing == 3 ? 0xf640 : 0xc400 | (timing << 12);
                const u32 values[] = {0x03000100, timing == 3 ? 0x040000a0 + (channel - 1) * 4 : 0x03001000,
                                      (control << 16) | 1};
                for (unsigned offset = 0; offset < 12; ++offset) {
                    m->dma.Write(channel, offset, values[offset / 4] >> ((offset & 3) * 8));
                }
                auto request = [&]() {
                    if (timing == 1) m->dma.Request(DMA::Occasion::VBlank);
                    else if (timing == 2) m->dma.Request(DMA::Occasion::HBlank);
                    else if (timing == 3) m->dma.Request(channel == 1 ? DMA::Occasion::FIFO0 : DMA::Occasion::FIFO1);
                };
                request();
                unsigned ready_mask = 0;
                for (unsigned cycle = 0; cycle <= 4; ++cycle) {
                    if (cycle != 0) m->scheduler.AddCycles(1);
                    if (cycle == 1) {
                        if (operation == 1 || operation == 2) m->dma.Write(channel, 11, (control >> 8) & 0x7f);
                        if (operation == 2 || operation == 3) m->dma.Write(channel, 11, control >> 8);
                        if (operation == 2 || operation == 4) request();
                    }
                    if (m->dma.IsRunning()) ready_mask |= 1 << cycle;
                }
                std::printf("%u,%u,%u,%u,%u\n", timing, channel, operation, ready_mask,
                            (m->dma.Read(channel, 11) >> 7) & 1);
            }
        }
    return std::fflush(stdout) ? 2 : 0;
}
