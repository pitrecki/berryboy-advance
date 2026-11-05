/* Accepted IRQ assertion/deassertion while a fetch waits, unmodified NBA. */
#include "nba-reference/probe-machine.hh"

int main() {
    std::puts("mode,source,change,edge,retired,cycle,r0,pc,cpsr,lr,dma_complete");
    for (unsigned thumb : {0, 1})
      for (u32 source : {0x03000000, 0x02000000, 0x08000000})
        for (unsigned drop : {0, 1}) {
          const unsigned duration = source == 0x03000000 ? 4 : source == 0x02000000 ? 9 : 11;
          for (unsigned edge : {0U, 1U, duration - 1, duration, duration + 1}) {
            auto m = std::make_unique<ProbeMachine>();
            const unsigned width = thumb ? 2 : 4;
            const u32 add = thumb ? 0x3001 : 0xe2800001;
            for (unsigned i = 0; i < 32; ++i)
                for (unsigned b = 0; b < width; ++b) m->bus.memory.iram[i * width + b] = add >> (b * 8);
            const u32 bx = 0xe12fff11;
            for (unsigned b = 0; b < 4; ++b) m->bus.memory.iram[0x80 + b] = bx >> (b * 8);
            const u32 nop = 0xe1a00000;
            for (unsigned i = 0; i < 3; ++i)
                for (unsigned b = 0; b < 4; ++b) m->bus.memory.bios[0x18 + i * 4 + b] = nop >> (b * 8);
            m->cpu.state.cpsr.v = 0x5f;
            m->cpu.state.r15 = thumb ? 0x03000080 : 0x03000000;
            m->cpu.state.r1 = 0x03000001;
            m->cpu.Run(); m->cpu.Run();
            if (thumb) m->cpu.Run();
            if (bool(m->cpu.state.cpsr.f.thumb) != bool(thumb)
                    || m->scheduler.GetTimestampNow() != (thumb ? 5U : 2U))
                throw std::runtime_error("Unexpected pipeline priming");
            const u32 dma[] = {source + 0x100, 0x03001000, 0xa4000001};
            for (unsigned b = 0; b < 12; ++b) m->dma.Write(3, b, dma[b / 4] >> ((b & 3) * 8));
            const auto start = m->scheduler.GetTimestampNow();
            m->cpu.IRQLine() = bool(drop);
            m->scheduler.Add(0, Scheduler::EventClass::DMA_activated, 0, 3);
            m->scheduler.Add(edge, Scheduler::EventClass::IRQ_update_irq_line, 0, !drop);
            m->scheduler.AddCycles(0);
            for (unsigned retired = 1; retired <= 2; ++retired) {
                m->cpu.Run();
                const auto pc = m->cpu.state.r15 - (m->cpu.state.cpsr.f.thumb ? 4 : 8);
                u32 word = 0;
                for (unsigned b = 0; b < 4; ++b) word |= u32(m->bus.memory.iram[0x1000 + b]) << (b * 8);
                std::printf("%s,0x%08x,%s,%u,%u,%llu,%u,0x%08x,0x%08x,0x%08x,%u\n",
                    thumb ? "thumb" : "arm", source, drop ? "deassert" : "assert", edge, retired,
                    (unsigned long long)(m->scheduler.GetTimestampNow() - start), m->cpu.state.r0, pc,
                    m->cpu.state.cpsr.v, m->cpu.state.r14, unsigned(word == 0x04030201));
            }
          }
        }
    return std::fflush(stdout) ? 2 : 0;
}
