/* Waiting opcode-fetch/IRQ acceptance across DMA release, unmodified NBA. */
#include "nba-reference/probe-machine.hh"

int main() {
    std::puts("mode,irq,next_ready,retired,cycle,r0,pc,cpsr,lr,second_dma_complete");
    for (unsigned thumb : {0, 1})
      for (unsigned irq : {0, 1})
        for (unsigned ready : {4, 5}) {
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
            m->cpu.state.cpsr.v = irq ? 0x5f : 0xdf;
            m->cpu.state.r15 = thumb ? 0x03000080 : 0x03000000;
            m->cpu.state.r1 = 0x03000001;
            m->cpu.Run(); m->cpu.Run();
            if (thumb) m->cpu.Run();
            if (bool(m->cpu.state.cpsr.f.thumb) != bool(thumb)
                    || m->scheduler.GetTimestampNow() != (thumb ? 5U : 2U))
                throw std::runtime_error("Unexpected pipeline priming");
            for (unsigned channel : {3, 1}) {
                const u32 dma[] = {0x03000100, 0x03001000 + (channel == 1 ? 0x100U : 0U),
                    (channel == 3 ? 0xa4000000U : 0x94000000U) | 1};
                for (unsigned b = 0; b < 12; ++b) m->dma.Write(channel, b, dma[b / 4] >> ((b & 3) * 8));
            }
            const auto start = m->scheduler.GetTimestampNow();
            m->scheduler.Add(0, Scheduler::EventClass::DMA_activated, 0, 3);
            m->scheduler.Add(ready, Scheduler::EventClass::DMA_activated, 0, 1);
            if (irq) m->scheduler.Add(ready, Scheduler::EventClass::IRQ_update_irq_line, 0, 1);
            m->scheduler.AddCycles(0); // Make DMA3 ready before the original CPU's fetch begins.
            for (unsigned retired = 1; retired <= 2; ++retired) {
                m->cpu.Run();
                u32 second_word = 0;
                for (unsigned b = 0; b < 4; ++b) second_word |= u32(m->bus.memory.iram[0x1100 + b]) << (b * 8);
                const auto pc = m->cpu.state.r15 - (m->cpu.state.cpsr.f.thumb ? 4 : 8);
                if (retired == 1 && (m->cpu.state.r0 != 1 || pc != 0x03000000 + width || second_word != 0))
                    throw std::runtime_error("Waiting ADD did not precede the next DMA/IRQ");
                if (retired == 2 && (m->cpu.state.r0 != (irq ? 1U : 2U)
                        || pc != (irq ? 0x1cU : 0x03000000 + width * 2)))
                    throw std::runtime_error("Unexpected second retirement");
                std::printf("%s,%u,%u,%u,%llu,%u,0x%08x,0x%08x,0x%08x,%u\n", thumb ? "thumb" : "arm", irq, ready,
                    retired, (unsigned long long)(m->scheduler.GetTimestampNow() - start), m->cpu.state.r0,
                    pc, m->cpu.state.cpsr.v,
                    m->cpu.state.r14, unsigned(second_word == 0x04030201));
            }
        }
    return std::fflush(stdout) ? 2 : 0;
}
