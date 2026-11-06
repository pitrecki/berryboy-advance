/* IRQ discarded-fetch page/prefetch observations, linked to unmodified NBA. */
#include "nba-reference/probe-machine.hh"
#include <cstring>

int main(int argc, char** argv) {
    const bool cartridge = argc == 2 && !std::strcmp(argv[1], "--cartridge-dma");
    if (argc != 1 && !cartridge) throw std::runtime_error("Usage: nba-cpu-irq-prefetch-probe [--cartridge-dma]");
    std::puts(cartridge ? "mode,code,prefetch,idle,dma_ready,cycle,pc,cpsr,lr,r0,dma_complete,source"
                        : "mode,code,prefetch,idle,dma_ready,cycle,pc,cpsr,lr,r0,dma_complete");
    for (unsigned thumb : {0, 1})
      for (u32 bank : {0x08000000, 0x0a000000, 0x0c000000})
        for (u32 offset : {0x20U, 0x20000U - (thumb ? 4U : 8U), 0x20000U - (thumb ? 2U : 4U), 0x20000U})
          for (unsigned setup : {0, 1, 2})
            for (int ready : {-1, 0, 1}) {
                if (cartridge && ready < 0) continue; // Existing matrix already covers no DMA.
                auto m = std::make_unique<ProbeMachine>();
                const unsigned width = thumb ? 2 : 4;
                const u32 add = thumb ? 0x3001 : 0xe2800001;
                std::vector<u8> image(0x20040, 0);
                for (unsigned i = 0; i < image.size(); ++i) image[i] = add >> ((i % width) * 8);
                m->bus.Attach(ROM(std::move(image), nullptr, nullptr));
                m->bus.hw.WriteHalf(0x04000204, setup ? 0x4000 : 0);
                const u32 bx = 0xe12fff11;
                for (unsigned b = 0; b < 4; ++b) m->bus.memory.iram[0x80 + b] = bx >> (b * 8);
                const u32 nop = 0xe1a00000;
                for (unsigned b = 0; b < 4; ++b) m->bus.memory.bios[0x18 + b] = nop >> (b * 8);
                const auto pc = bank + offset;
                m->cpu.state.cpsr.v = 0x5f;
                m->cpu.state.r15 = thumb ? 0x03000080 : pc;
                m->cpu.state.r1 = pc | 1;
                m->cpu.Run(); m->cpu.Run();
                if (thumb) m->cpu.Run();
                if (m->cpu.state.r15 - (thumb ? 4 : 8) != pc || bool(m->cpu.state.cpsr.f.thumb) != bool(thumb))
                    throw std::runtime_error("Incorrect primed pipeline");
                const unsigned idle = setup == 2 ? 24 : 0;
                for (unsigned i = 0; i < idle; ++i) m->bus.Idle();
                const auto start = m->scheduler.GetTimestampNow();
                m->cpu.IRQLine() = true;
                const u32 source = cartridge ? 0x08001000 : 0x03000100;
                if (ready >= 0) {
                    const u32 dma[] = {source, 0x03001000, 0xa4000001};
                    for (unsigned b = 0; b < 12; ++b) m->dma.Write(3, b, dma[b / 4] >> ((b & 3) * 8));
                    m->scheduler.Add(ready, Scheduler::EventClass::DMA_activated, 0, 3);
                }
                m->scheduler.AddCycles(0);
                m->cpu.Run();
                if (m->cpu.state.r15 - 8 != 0x1c || m->cpu.state.r14 != pc + 4 || m->cpu.state.r0 != 0)
                    throw std::runtime_error("Unexpected IRQ handler state");
                u32 word = 0;
                for (unsigned b = 0; b < 4; ++b) word |= u32(m->bus.memory.iram[0x1000 + b]) << (b * 8);
                const u32 expected = cartridge ? (thumb ? 0x30013001 : add) : 0x04030201;
                std::printf("%s,0x%08x,%u,%u,%d,%llu,0x%08x,0x%08x,0x%08x,%u,%u", thumb ? "thumb" : "arm",
                    pc, unsigned(setup != 0), idle, ready, (unsigned long long)(m->scheduler.GetTimestampNow() - start),
                    m->cpu.state.r15 - 8, m->cpu.state.cpsr.v, m->cpu.state.r14, m->cpu.state.r0, unsigned(word == expected));
                if (cartridge) std::printf(",0x%08x", source);
                std::puts("");
            }
    return std::fflush(stdout) ? 2 : 0;
}
