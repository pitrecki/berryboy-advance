/* Discarded IRQ opcode-fetch wait states through the unmodified NBA CPU. */
#include "nba-reference/probe-machine.hh"

int main() {
    std::puts("mode,code,dma_ready,cycle,pc,cpsr,lr,r0,dma_complete");
    for (unsigned thumb : {0, 1})
      for (u32 code : {0x03000000, 0x02000000, 0x08000000})
        for (int ready : {-1, 0, 1}) {
            auto m = std::make_unique<ProbeMachine>();
            const unsigned width = thumb ? 2 : 4;
            const u32 add = thumb ? 0x3001 : 0xe2800001;
            std::vector<u8> image(512, 0);
            for (unsigned i = 0; i < 32; ++i)
                for (unsigned b = 0; b < width; ++b) {
                    const u8 value = add >> (b * 8);
                    image[i * width + b] = value;
                    if (code == 0x03000000) m->bus.memory.iram[i * width + b] = value;
                    if (code == 0x02000000) m->bus.memory.wram[i * width + b] = value;
                }
            m->bus.Attach(ROM(std::move(image), nullptr, nullptr));
            const u32 bx = 0xe12fff11;
            for (unsigned b = 0; b < 4; ++b) m->bus.memory.iram[0x80 + b] = bx >> (b * 8);
            const u32 nop = 0xe1a00000;
            for (unsigned b = 0; b < 4; ++b) m->bus.memory.bios[0x18 + b] = nop >> (b * 8);
            const auto observed_pc = code + 0x20;
            m->cpu.state.cpsr.v = 0x5f;
            m->cpu.state.r15 = thumb ? 0x03000080 : observed_pc;
            m->cpu.state.r1 = observed_pc | 1;
            m->cpu.Run(); m->cpu.Run();
            if (thumb) m->cpu.Run();
            const unsigned warmup = thumb ? (code == 0x03000000 ? 5 : code == 0x02000000 ? 9 : 11)
                    : (code == 0x03000000 ? 2 : code == 0x02000000 ? 12 : 14);
            if (m->scheduler.GetTimestampNow() != warmup || bool(m->cpu.state.cpsr.f.thumb) != bool(thumb))
                throw std::runtime_error("Unexpected pipeline priming");
            const auto start = m->scheduler.GetTimestampNow();
            m->cpu.IRQLine() = true;
            if (ready >= 0) {
                const u32 dma[] = {0x03000100, 0x03001000, 0xa4000001};
                for (unsigned b = 0; b < 12; ++b) m->dma.Write(3, b, dma[b / 4] >> ((b & 3) * 8));
                m->scheduler.Add(ready, Scheduler::EventClass::DMA_activated, 0, 3);
            }
            m->scheduler.AddCycles(0);
            m->cpu.Run(); // Accepted IRQ, refill, then first synthetic handler NOP.
            if (m->cpu.state.r15 - 8 != 0x1c || m->cpu.state.r14 != observed_pc + 4 || m->cpu.state.r0 != 0)
                throw std::runtime_error("Unexpected IRQ handler state");
            u32 word = 0;
            for (unsigned b = 0; b < 4; ++b) word |= u32(m->bus.memory.iram[0x1000 + b]) << (b * 8);
            std::printf("%s,0x%08x,%d,%llu,0x%08x,0x%08x,0x%08x,%u,%u\n",
                thumb ? "thumb" : "arm", observed_pc, ready,
                (unsigned long long)(m->scheduler.GetTimestampNow() - start), m->cpu.state.r15 - 8,
                m->cpu.state.cpsr.v, m->cpu.state.r14, m->cpu.state.r0, unsigned(word == 0x04030201));
        }
    return std::fflush(stdout) ? 2 : 0;
}
