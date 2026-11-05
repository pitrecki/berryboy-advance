/* Instruction/exception refill boundaries through the unmodified NBA core. */
#include "nba-reference/probe-machine.hh"

struct RefillForm { const char* name; bool thumb; u32 opcode; unsigned kind; };

int main() {
    const RefillForm forms[] = {
        {"arm-b", false, 0xea0000fe, 0}, {"arm-bl", false, 0xeb0000fe, 0},
        {"arm-bx-thumb", false, 0xe12fff12, 1}, {"thumb-bx-arm", true, 0x4710, 1},
        {"arm-ldr-pc", false, 0xe592f000, 2}, {"arm-ldm-pc", false, 0xe8b28000, 2},
        {"thumb-b", true, 0xe1fe, 0}, {"thumb-pop-pc", true, 0xbd00, 2},
        {"arm-swi", false, 0xef000000, 3}, {"thumb-swi", true, 0xdf00, 3},
        {"arm-irq", false, 0xe1a00000, 4}, {"thumb-irq", true, 0x46c0, 4}
    };
    std::puts("program,source,ready,cycle,pc,cpsr,lr,r2,sp,dma_complete");
    for (auto form : forms)
      for (u32 source : {0x03000000, 0x02000000, 0x08000000})
        for (unsigned ready : {0, 1, 2, 3}) {
            auto m = std::make_unique<ProbeMachine>();
            const u32 handler_nop = 0xe1a00000;
            for (unsigned b = 0; b < 4; ++b) m->bus.memory.bios[0x18 + b] = handler_nop >> (b * 8);
            const unsigned width = form.thumb ? 2 : 4;
            for (unsigned i = 0; i < 32; ++i)
                for (unsigned b = 0; b < width; ++b) m->bus.memory.iram[i * width + b] = form.opcode >> (b * 8);
            const u32 bx = 0xe12fff11;
            for (unsigned b = 0; b < 4; ++b) m->bus.memory.iram[0x80 + b] = bx >> (b * 8);
            const bool irq = form.kind == 4;
            const u32 target = 0x03000400;
            const bool target_thumb = form.kind == 1 ? !form.thumb : form.thumb;
            m->cpu.state.cpsr.v = irq ? 0x5f : 0xdf;
            m->cpu.state.r15 = form.thumb ? 0x03000080 : 0x03000000;
            m->cpu.state.r1 = 0x03000001;
            m->cpu.state.r2 = form.kind == 2 ? 0x03000200 : target | unsigned(target_thumb);
            m->cpu.state.r13 = 0x03000200;
            for (unsigned b = 0; b < 4; ++b) m->bus.memory.iram[0x200 + b] = (target | unsigned(target_thumb)) >> (b * 8);
            m->cpu.Run(); m->cpu.Run();
            if (form.thumb) m->cpu.Run();
            if (bool(m->cpu.state.cpsr.f.thumb) != form.thumb
                    || m->scheduler.GetTimestampNow() != (form.thumb ? 5U : 2U))
                throw std::runtime_error("Unexpected pipeline priming");
            if (ready) {
                const u32 dma[] = {source + 0x100, 0x03001000, 0xa4000002};
                for (unsigned b = 0; b < 12; ++b) m->dma.Write(3, b, dma[b / 4] >> ((b & 3) * 8));
                // Normalize readiness directly through NBA's registered DMA
                // activation event; no production activation latency is tested.
                m->scheduler.Add(ready, Scheduler::EventClass::DMA_activated, 0, 3);
            }
            const auto start = m->scheduler.GetTimestampNow();
            // The public Run() enters IRQ and executes the first handler NOP.
            // Observe that same boundary in both adapters, without private hooks.
            if (irq) m->cpu.IRQLine() = true;
            m->cpu.Run();
            const auto pc = m->cpu.state.r15 - (m->cpu.state.cpsr.f.thumb ? 4 : 8);
            if (pc != (irq ? 0x1cU : form.kind == 3 ? 8U : target))
                throw std::runtime_error("Unexpected refill result");
            u32 last_word = 0;
            for (unsigned b = 0; b < 4; ++b) last_word |= u32(m->bus.memory.iram[0x1004 + b]) << (b * 8);
            std::printf("%s,0x%08x,%u,%llu,0x%08x,0x%08x,0x%08x,0x%08x,0x%08x,%u\n",
                form.name, source, ready, (unsigned long long)(m->scheduler.GetTimestampNow() - start),
                pc, m->cpu.state.cpsr.v, m->cpu.state.r14, m->cpu.state.r2, m->cpu.state.r13,
                unsigned(last_word == 0x08070605));
        }
    return std::fflush(stdout) ? 2 : 0;
}
