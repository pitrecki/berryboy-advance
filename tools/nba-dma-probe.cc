/* Original DMA ownership probe linked to an unmodified NanoBoyAdvance core.
 * No commercial ROM or BIOS. Device classes use their original scheduler/bus;
 * only the desktop frontend is omitted from this standalone composition.
 */
#include "nba-reference/probe-machine.hh"

int main() {
    std::puts("source,channels,count,service_delay,ownership_cycles");
    for (u32 region : {0x03000000, 0x02000000, 0x08000000}) {
        for (unsigned channels : {1, 2}) {
            for (unsigned count : {0, 1, 2, 3, 4, 5, 8, 0x3fff}) {
                // NBA activates a request after two cycles. Exclude that delay
                // from ownership costs; do not force an early DMA activation.
                for (unsigned late : {2, 3}) {
                    auto machine = std::make_unique<ProbeMachine>();
                    for (unsigned c = 1; c <= channels; ++c) machine->Configure(c, region + 0x100, count);
                    machine->dma.Request(DMA::Occasion::FIFO0);
                    if (channels == 2) machine->dma.Request(DMA::Occasion::FIFO1);
                    machine->scheduler.AddCycles(late);
                    if (!machine->dma.IsRunning()) throw std::runtime_error("DMA did not activate");
                    const auto start = machine->scheduler.GetTimestampNow();
                    const auto elapsed = machine->dma.Run();
                    auto state = std::make_unique<SaveState>();
                    machine->dma.CopyState(*state);
                    if (machine->dma.IsRunning() || machine->scheduler.GetTimestampNow() - start != unsigned(elapsed)) {
                        throw std::runtime_error("DMA did not finish at the reported clock");
                    }
                    for (unsigned c = 1; c <= channels; ++c) {
                        if (state->dma.channels[c].latch.src_address != region + 0x110
                                || state->dma.channels[c].latch.length != 4) {
                            throw std::runtime_error("FIFO source progress/repeat count mismatch");
                        }
                    }
                    std::printf("0x%08x,%u,%u,%u,%d\n", region, channels, count, late, elapsed);
                }
            }
        }
    }
    return std::fflush(stdout) ? 2 : 0;
}
