/* Original DMA ownership probe linked to an unmodified NanoBoyAdvance core.
 * No commercial ROM or BIOS. Device classes use their original scheduler/bus;
 * only the desktop frontend is omitted from this standalone composition.
 */
#include <nba/config.hh>
#include <nba/rom/rom.hh>
#include <nba/save_state.hh>
#include "arm/arm7tdmi.hh"
#include "bus/bus.hh"
#include <cstdio>
#include <memory>
#include <stdexcept>
#include <vector>

using namespace nba;
using namespace nba::core;

struct ProbeMachine {
    std::shared_ptr<Config> config = std::make_shared<Config>();
    Scheduler scheduler;
    arm::ARM7TDMI cpu;
    IRQ irq;
    DMA dma;
    APU apu;
    PPU ppu;
    Timer timer;
    KeyPad keypad;
    Bus bus;

    ProbeMachine() : cpu(scheduler, bus), irq(cpu, scheduler), dma(bus, irq, scheduler),
        apu(scheduler, dma, bus, config), ppu(scheduler, irq, dma, config),
        timer(scheduler, irq, apu), keypad(scheduler, irq),
        bus{scheduler, {cpu, irq, dma, apu, ppu, timer, keypad, nullptr, {}}} {
        scheduler.Reset(); cpu.Reset(); irq.Reset(); dma.Reset(); timer.Reset();
        apu.Reset(); ppu.Reset(); bus.Reset(); keypad.Reset();
        std::vector<u8> image(512, 0);
        for (unsigned i = 0; i < 16; ++i) {
            image[0x100 + i] = i + 1;
            bus.memory.iram[0x100 + i] = i + 1;
            bus.memory.wram[0x100 + i] = i + 1;
        }
        bus.Attach(ROM(std::move(image), nullptr, nullptr));
        bus.hw.WriteHalf(0x04000084, 0x80);
        bus.hw.WriteHalf(0x04000082, 0x210c);
    }

    void Configure(unsigned channel, u32 source, unsigned count) {
        const u32 values[] = {source, 0x040000a0 + (channel - 1) * 4, 0xb6400000 | count};
        for (unsigned offset = 0; offset < 12; ++offset) {
            dma.Write(channel, offset, values[offset / 4] >> ((offset & 3) * 8));
        }
    }
};

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
