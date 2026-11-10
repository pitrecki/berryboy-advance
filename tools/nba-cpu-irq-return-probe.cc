/* IRQ return and subsequent cartridge stream, linked to the pinned unmodified NBA. */
#include "nba-reference/probe-machine.hh"
#include <cstring>

int main(int argc, char** argv) {
    bool full = false, extended = false;
    for (int i = 1; i < argc; ++i) {
        if (!std::strcmp(argv[i], "--full-trace") && !full) full = true;
        else if (!std::strcmp(argv[i], "--extended-pages") && !extended) extended = true;
        else { std::fputs("Usage: nba-cpu-irq-return-probe [--full-trace] [--extended-pages]\n", stderr); return 2; }
    }
    std::puts(full ? "mode,code,prefetch,idle,stage,cycle,pc,cpsr,lr,r0"
                   : "mode,code,prefetch,idle,add,cycles_since_return,pc,cpsr,lr,r0");
    for (unsigned thumb : {0, 1})
      for (u32 bank : {0x08000000, 0x0a000000, 0x0c000000})
        for (u32 offset : {0x20U, 0x20000U - (thumb ? 4U : 8U), 0x20000U - (thumb ? 2U : 4U), 0x20000U})
          for (unsigned setup : {0, 1, 2}) {
            if (!extended && offset == 0x20000U - (thumb ? 4U : 8U)) continue;
            auto m = std::make_unique<ProbeMachine>();
            const unsigned width = thumb ? 2 : 4;
            const u32 add = thumb ? 0x3001 : 0xe2800001;
            std::vector<u8> image(0x20040, 0);
            for (unsigned i = 0; i < image.size(); ++i) image[i] = add >> ((i % width) * 8);
            m->bus.Attach(ROM(std::move(image), nullptr, nullptr));
            m->bus.hw.WriteHalf(0x04000204, setup ? 0x4000 : 0);
            const auto write = [&](unsigned address, u32 word) {
                for (unsigned b = 0; b < 4; ++b) m->bus.memory.bios[address + b] = word >> (b * 8);
            };
            write(0x18, 0xe1a00000); write(0x1c, 0xe25ef004);
            const u32 bx = 0xe12fff11;
            for (unsigned b = 0; b < 4; ++b) m->bus.memory.iram[0x80 + b] = bx >> (b * 8);
            const auto pc = bank + offset;
            m->cpu.state.cpsr.v = 0xa000005f;
            m->cpu.state.r15 = thumb ? 0x03000080 : pc;
            m->cpu.state.r1 = pc | 1;
            m->cpu.Run(); m->cpu.Run(); if (thumb) m->cpu.Run();
            if (m->cpu.state.r15 - (thumb ? 4 : 8) != pc || bool(m->cpu.state.cpsr.f.thumb) != bool(thumb))
                throw std::runtime_error("Incorrect primed pipeline");
            m->cpu.state.r0 = 0;
            const unsigned idle = setup == 2 ? 24 : 0;
            for (unsigned i = 0; i < idle; ++i) m->bus.Idle();
            const auto start = m->scheduler.GetTimestampNow();
            m->cpu.IRQLine() = true;
            u64 returned = 0;
            for (unsigned stage = 1; stage <= 5; ++stage) {
                m->cpu.Run();
                const auto logical_pc = m->cpu.state.r15 - (m->cpu.state.cpsr.f.thumb ? 4 : 8);
                if (stage == 2) returned = m->scheduler.GetTimestampNow();
                if (full || stage > 2) std::printf("%s,0x%08x,%u,%u,%u,%llu,0x%08x,0x%08x,0x%08x,%u\n", thumb ? "thumb" : "arm", pc,
                    unsigned(setup != 0), idle, full ? stage : stage - 2, (unsigned long long)(m->scheduler.GetTimestampNow() - (full ? start : returned)),
                    logical_pc, m->cpu.state.cpsr.v, m->cpu.state.r14, m->cpu.state.r0);
                if (stage == 1) m->cpu.IRQLine() = false;
            }
          }
    return std::fflush(stdout) ? 2 : 0;
}
