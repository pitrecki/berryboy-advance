/* Branch-refill observations through the pinned, unmodified NBA CPU. */
#include "nba-reference/probe-machine.hh"

int main() {
    std::puts("mode,target,source,ready,cycle,pc,dma_complete");
    for (unsigned thumb : {0, 1})
      for (u32 target : {0x03000400, 0x08000100})
        for (u32 source : {0x03000000, 0x02000000, 0x08000000})
          for (unsigned ready : {0, 1, 2}) {
            auto m = std::make_unique<ProbeMachine>();
            const unsigned width = thumb ? 2 : 4;
            const u32 opcode = thumb ? 0x4710 : 0xe12fff12; // BX r2, same instruction set.
            for (unsigned i = 0; i < 32; ++i)
                for (unsigned b = 0; b < width; ++b) m->bus.memory.iram[i * width + b] = opcode >> (b * 8);
            const u32 bx = 0xe12fff11;
            for (unsigned b = 0; b < 4; ++b) m->bus.memory.iram[0x80 + b] = bx >> (b * 8);
            m->cpu.state.cpsr.v = 0xdf;
            m->cpu.state.r15 = thumb ? 0x03000080 : 0x03000000;
            m->cpu.state.r1 = 0x03000001;
            m->cpu.state.r2 = target | thumb;
            m->cpu.Run(); m->cpu.Run();
            if (thumb) m->cpu.Run();
            if (bool(m->cpu.state.cpsr.f.thumb) != bool(thumb)
                    || m->scheduler.GetTimestampNow() != (thumb ? 5U : 2U))
                throw std::runtime_error("Unexpected pipeline priming");
            m->cpu.state.cpsr.v = thumb ? 0xff : 0xdf;
            if (ready) {
                const u32 dma[] = {source + 0x100, 0x03000200, 0x84000002};
                for (unsigned b = 0; b < 12; ++b) m->dma.Write(3, b, dma[b / 4] >> ((b & 3) * 8));
                m->scheduler.AddCycles(2 - ready);
            }
            const auto start = m->scheduler.GetTimestampNow();
            m->cpu.Run();
            const auto pc = m->cpu.state.r15 - (thumb ? 4 : 8);
            if (pc != target || bool(m->cpu.state.cpsr.f.thumb) != bool(thumb))
                throw std::runtime_error("Unexpected branch result");
            u32 last_word = 0;
            for (unsigned b = 0; b < 4; ++b) last_word |= u32(m->bus.memory.iram[0x204 + b]) << (b * 8);
            const bool complete = last_word == 0x08070605;
            if (complete != bool(ready)) throw std::runtime_error("Unexpected DMA progress at branch retirement");
            std::printf("%s,0x%08x,0x%08x,%u,%llu,0x%08x,%u\n",
                thumb ? "thumb" : "arm", target, source, ready,
                (unsigned long long)(m->scheduler.GetTimestampNow() - start), pc, unsigned(complete));
          }
    return std::fflush(stdout) ? 2 : 0;
}
