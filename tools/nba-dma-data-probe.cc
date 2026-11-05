/* Data-bus arbitration through the pinned, unmodified NBA CPU and DMA. */
#include "nba-reference/probe-machine.hh"

int main() {
    std::puts("mode,program,data,source,ready,cycle,r0,r3,r2,pc");
    for (unsigned thumb : {0, 1})
      for (unsigned multiple : {0, 1})
       for (u32 data : {0x03000200, 0x08000100})
        for (u32 source : {0x03000000, 0x02000000, 0x08000000})
          for (unsigned ready : {1, 2}) {
            auto m = std::make_unique<ProbeMachine>();
            const u32 opcode = multiple ? (thumb ? 0xca09 : 0xe8b20009) : (thumb ? 0x6810 : 0xe5920000);
            const unsigned width = thumb ? 2 : 4;
            for (unsigned i = 0; i < 32; ++i)
                for (unsigned b = 0; b < width; ++b) m->bus.memory.iram[i * width + b] = opcode >> (b * 8);
            const u32 bx = 0xe12fff11;
            for (unsigned b = 0; b < 4; ++b) m->bus.memory.iram[0x80 + b] = bx >> (b * 8);
            for (unsigned i = 0; i < 2; ++i)
                for (unsigned b = 0; b < 4; ++b) m->bus.memory.iram[0x200 + i * 4 + b] = (0x12345678U + i) >> (b * 8);
            m->cpu.state.cpsr.v = 0xdf;
            m->cpu.state.r15 = thumb ? 0x03000080 : 0x03000000;
            m->cpu.state.r1 = 0x03000001;
            m->cpu.state.r2 = data;
            m->cpu.Run(); m->cpu.Run();
            if (thumb) m->cpu.Run();
            if (bool(m->cpu.state.cpsr.f.thumb) != bool(thumb)
                    || m->scheduler.GetTimestampNow() != (thumb ? 5U : 2U))
                throw std::runtime_error("Unexpected pipeline priming");
            m->cpu.state.cpsr.v = thumb ? 0xff : 0xdf;
            m->cpu.state.r0 = 0;
            m->cpu.state.r3 = 0;
            const u32 dma[] = {source + 0x100, 0x03000200, 0x84000002}; // Immediate, two words.
            for (unsigned b = 0; b < 12; ++b) m->dma.Write(3, b, dma[b / 4] >> ((b & 3) * 8));
            // NBA activates two cycles after enable. Age the request by one
            // cycle to put readiness at the end of the first opcode fetch.
            m->scheduler.AddCycles(2 - ready);
            const auto start = m->scheduler.GetTimestampNow();
            m->cpu.Run();
            std::printf("%s,%s,0x%08x,0x%08x,%u,%llu,%u,%u,0x%08x,0x%08x\n",
                thumb ? "thumb" : "arm", multiple ? "ldm" : "ldr", data, source, ready,
                (unsigned long long)(m->scheduler.GetTimestampNow() - start),
                m->cpu.state.r0, m->cpu.state.r3, m->cpu.state.r2, m->cpu.state.r15 - (thumb ? 4 : 8));
          }
    return std::fflush(stdout) ? 2 : 0;
}
