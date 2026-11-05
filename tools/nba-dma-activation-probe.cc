/* Original CPU/DMA activation experiment, linked to the pinned unmodified NBA. */
#include "nba-reference/probe-machine.hh"

int main() {
    std::puts("mode,program,source,channels,retired,cycle,r0,pc");
    for (unsigned thumb : {0, 1})
      for (unsigned load : {0, 1})
        for (u32 source : {0x03000000, 0x02000000, 0x08000000})
            for (unsigned channels : {1, 2}) {
                auto m = std::make_unique<ProbeMachine>();
                const auto width = thumb ? 2U : 4U;
                for (unsigned i = 0; i < 32; ++i) {
                    const u32 opcode = load ? (thumb ? 0x6810 : 0xe5920000) : (thumb ? 0x3001 : 0xe2800001);
                    for (unsigned b = 0; b < width; ++b) m->bus.memory.iram[i * width + b] = opcode >> (b * 8);
                }
                // Reset's dummy pipeline entries are ARM opcodes. Prime in ARM;
                // enter Thumb through a real BX so the original CPU refills it.
                const u32 bx = 0xe12fff11;
                for (unsigned b = 0; b < 4; ++b) m->bus.memory.iram[0x80 + b] = bx >> (b * 8);
                m->cpu.state.cpsr.v = 0xdf;
                m->cpu.state.r15 = thumb ? 0x03000080 : 0x03000000;
                m->cpu.state.r1 = 0x03000001;
                m->cpu.state.r2 = 0x03000200;
                const u32 data = 0x12345678;
                for (unsigned b = 0; b < 4; ++b) m->bus.memory.iram[0x200 + b] = data >> (b * 8);
                m->cpu.Run(); m->cpu.Run(); // Fill the original two-slot pipeline.
                if (thumb) m->cpu.Run();
                if (bool(m->cpu.state.cpsr.f.thumb) != bool(thumb)) throw std::runtime_error("Incorrect CPU mode after priming");
                m->cpu.state.cpsr.v = thumb ? 0xff : 0xdf;
                m->cpu.state.r0 = 0;
                const auto start = m->scheduler.GetTimestampNow();
                if (start != (thumb ? 5U : 2U)) throw std::runtime_error("Unexpected pipeline priming cycles");
                for (unsigned c = 1; c <= channels; ++c) m->Configure(c, source + 0x100, 4);
                m->dma.Request(DMA::Occasion::FIFO0);
                if (channels == 2) m->dma.Request(DMA::Occasion::FIFO1);
                for (unsigned retired = 1; retired <= 4; ++retired) {
                    m->cpu.Run();
                    if (m->cpu.state.r0 != (load ? data : retired)
                            || m->cpu.state.r15 - (thumb ? 4 : 8) != 0x03000000 + retired * width) {
                        throw std::runtime_error("CPU pipeline did not execute the expected instruction");
                    }
                    if (load && retired == 1) {
                        auto state = std::make_unique<SaveState>();
                        m->dma.CopyState(*state);
                        for (unsigned c = 1; c <= channels; ++c) {
                            if (state->dma.channels[c].latch.src_address != source + 0x110) {
                                throw std::runtime_error("Load retired before the FIFO burst completed");
                            }
                        }
                    }
                    std::printf("%s,%s,0x%08x,%u,%u,%llu,%u,0x%08x\n", thumb ? "thumb" : "arm", load ? "load" : "add", source, channels,
                        retired, (unsigned long long)(m->scheduler.GetTimestampNow() - start),
                        m->cpu.state.r0, m->cpu.state.r15 - (thumb ? 4 : 8));
                }
            }
    return std::fflush(stdout) ? 2 : 0;
}
