/* Self-modifying RAM programs, linked to the pinned unmodified NBA. */
#include "nba-reference/probe-machine.hh"

int main(int argc, char**) {
    if (argc != 1) { std::fputs("Usage: nba-cpu-self-modify-probe\n", stderr); return 2; }
    std::puts("mode,code,store_width,distance,stage,cycles,pc,cpsr,r0,patched");
    for (unsigned thumb : {0, 1})
      for (u32 region : {0x02000000, 0x03000000})
        for (unsigned store : {1, 2, 4})
          for (unsigned distance = 1; distance <= 4; ++distance) {
            auto m = std::make_unique<ProbeMachine>();
            const unsigned width = thumb ? 2 : 4;
            const u32 code = region + 0x100, target = code + distance * width;
            const auto put = [&](u32 address, u32 value, unsigned bytes) {
                auto* memory = (address >> 24) == 2 ? m->bus.memory.wram.data() : m->bus.memory.iram.data();
                for (unsigned b = 0; b < bytes; ++b) memory[(address & 0x7fff) + b] = value >> (b * 8);
            };
            put(0x03000080, 0xe12fff14, 4);
            put(code, thumb ? (store == 1 ? 0x7011 : store == 2 ? 0x8011 : 0x6011)
                           : (store == 1 ? 0xe5c21000 : store == 2 ? 0xe1c210b0 : 0xe5821000), width);
            for (unsigned i = 1; i <= 4; ++i) put(code + i * width, thumb ? 0x2000 | i : 0xe3b00000 | i, width);
            put(code + 5 * width, thumb ? 0x4718 : 0xe12fff13, width);
            u32 patch = thumb ? 0x2009 : 0xe3b00009;
            if (thumb && store == 4) patch = (target & 2)
                ? 0x20090000 | (distance == 1 ? 0x6011 : 0x2000 | (distance - 1))
                : patch | (distance == 4 ? 0x4718 : 0x2000 | (distance + 1)) << 16;
            m->cpu.state.cpsr.v = 0xdf;
            m->cpu.state.r15 = 0x03000080;
            m->cpu.state.r1 = patch; m->cpu.state.r2 = store == 4 ? target & ~3U : target;
            m->cpu.state.r3 = target | thumb; m->cpu.state.r4 = code | thumb;
            m->cpu.Run(); m->cpu.Run(); m->cpu.Run();
            if (m->cpu.state.r15 - (thumb ? 4 : 8) != code || bool(m->cpu.state.cpsr.f.thumb) != bool(thumb))
                throw std::runtime_error("Incorrect priming");
            const auto start = m->scheduler.GetTimestampNow();
            for (unsigned stage = 1; stage <= 7; ++stage) {
                m->cpu.Run();
                const auto* memory = (target >> 24) == 2 ? m->bus.memory.wram.data() : m->bus.memory.iram.data();
                u32 patched = 0;
                for (unsigned b = 0; b < width; ++b) patched |= u32(memory[(target & 0x7fff) + b]) << (b * 8);
                std::printf("%s,0x%08x,%u,%u,%u,%llu,0x%08x,0x%08x,%u,0x%08x\n",
                    thumb ? "thumb" : "arm", code, store, distance, stage,
                    (unsigned long long)(m->scheduler.GetTimestampNow() - start),
                    m->cpu.state.r15 - (thumb ? 4 : 8), m->cpu.state.cpsr.v, m->cpu.state.r0, patched);
            }
          }
    return std::fflush(stdout) ? 3 : 0;
}
