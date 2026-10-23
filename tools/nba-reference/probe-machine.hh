/* Original headless composition of unmodified NBA device classes. */
#pragma once
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
