/* Self-modifying RAM programs, linked to the pinned unmodified NBA. */
#include "nba-reference/probe-machine.hh"
#include <cstring>

int main(int argc, char** argv) {
    const bool extended = argc == 2 && !std::strcmp(argv[1], "--extended-memory");
    if (argc != 1 && !extended) { std::fputs("Usage: nba-cpu-self-modify-probe [--extended-memory]\n", stderr); return 2; }
    std::puts(extended ? "mode,code,patch_base,store_width,distance,stage,cycles,pc,cpsr,r0,patched"
                       : "mode,code,store_width,distance,stage,cycles,pc,cpsr,r0,patched");
    const u32 locations[][2] = {
        {0x02000100, 0x02000100}, {0x03000100, 0x03000100},
        {0x02000100, 0x02040100}, {0x02040100, 0x02000100}, {0x02fc0100, 0x02000100},
        {0x03000100, 0x03008100}, {0x03008100, 0x03000100}, {0x03ff8100, 0x03000100},
        {0x06000100, 0x06020100}, {0x06020100, 0x06000100},
        {0x06010100, 0x06018100}, {0x06018100, 0x06010100},
        {0x05000100, 0x05000500}, {0x05000500, 0x05000100},
        {0x07000100, 0x07000500}, {0x07000500, 0x07000100}
    };
    for (unsigned thumb : {0, 1})
      for (unsigned location = 0; location < (extended ? 16U : 2U); ++location)
        for (unsigned store : {1, 2, 4})
          for (unsigned distance = 1; distance <= 4; ++distance) {
            const u32 code = locations[location][0], patch_base = locations[location][1];
            if ((code >> 24) >= 5 && store == 1) continue;
            auto m = std::make_unique<ProbeMachine>();
            if (extended) m->bus.hw.WriteHalf(0x04000000, 0x80); // Isolate CPU latching from display contention.
            const unsigned width = thumb ? 2 : 4;
            const u32 target = code + distance * width, destination = patch_base + distance * width;
            const auto byte = [&](u32 address) -> u8& {
                switch (address >> 24) {
                    case 2: return m->bus.memory.wram[address & 0x3ffff];
                    case 3: return m->bus.memory.iram[address & 0x7fff];
                    case 5: return m->ppu.GetPRAM()[address & 0x3ff];
                    case 6: {
                        auto offset = address & 0x1ffff;
                        return m->ppu.GetVRAM()[offset >= 0x18000 ? offset - 0x8000 : offset];
                    }
                    case 7: return m->ppu.GetOAM()[address & 0x3ff];
                    default: throw std::runtime_error("Unmapped probe initialization");
                }
            };
            const auto put = [&](u32 address, u32 value, unsigned bytes) {
                for (unsigned b = 0; b < bytes; ++b) byte(address + b) = value >> (b * 8);
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
            m->cpu.state.r1 = patch; m->cpu.state.r2 = store == 4 ? destination & ~3U : destination;
            m->cpu.state.r3 = target | thumb; m->cpu.state.r4 = code | thumb;
            m->cpu.Run(); m->cpu.Run(); m->cpu.Run();
            if (m->cpu.state.r15 - (thumb ? 4 : 8) != code || bool(m->cpu.state.cpsr.f.thumb) != bool(thumb))
                throw std::runtime_error("Incorrect priming");
            const auto start = m->scheduler.GetTimestampNow();
            for (unsigned stage = 1; stage <= 7; ++stage) {
                m->cpu.Run();
                u32 patched = 0;
                for (unsigned b = 0; b < width; ++b) patched |= u32(byte(target + b)) << (b * 8);
                std::printf("%s,0x%08x,", thumb ? "thumb" : "arm", code);
                if (extended) std::printf("0x%08x,", patch_base);
                std::printf("%u,%u,%u,%llu,0x%08x,0x%08x,%u,0x%08x\n", store, distance, stage,
                    (unsigned long long)(m->scheduler.GetTimestampNow() - start),
                    m->cpu.state.r15 - (thumb ? 4 : 8), m->cpu.state.cpsr.v, m->cpu.state.r0, patched);
            }
          }
    return std::fflush(stdout) ? 3 : 0;
}
