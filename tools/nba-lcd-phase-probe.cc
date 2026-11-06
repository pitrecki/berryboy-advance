/* Asset-free observations of natural LCD status, DMA readiness and IRQ phases.
 * CPU execution is deliberately excluded. Link the pinned, unmodified NBA core.
 */
#include "nba-reference/probe-machine.hh"
#include <array>

struct LcdPhaseProbe {
    ProbeMachine machine;
    std::unique_ptr<SaveState> state = std::make_unique<SaveState>();
    unsigned line, forced_blank;
    u64 start = 0;
    bool trace;
    static constexpr unsigned duration = 1248;

    LcdPhaseProbe(unsigned target, unsigned blank, bool observed)
        : line(target), forced_blank(blank), trace(observed) {}

    auto Values() -> std::array<u32, 9> {
        machine.dma.CopyState(*state);
        return {machine.ppu.mmio.vcount, machine.ppu.mmio.dispstat.ReadHalf(),
            machine.irq.ReadHalf(2), machine.irq.ReadHalf(4), unsigned(machine.cpu.IRQLine()),
            unsigned(state->dma.channels[1].event_uid != 0), unsigned(machine.dma.IsRunning()),
            unsigned(machine.dma.Read(1, 11) >> 7),
            unsigned(machine.bus.memory.iram[0x1000]) | (unsigned(machine.bus.memory.iram[0x1001]) << 8)};
    }
    void Sample() {
        const auto cycle = machine.scheduler.GetTimestampNow() - start;
        const auto values = Values();
        std::printf("%u,%u,%llu", line, forced_blank, (unsigned long long)cycle);
        for (auto value : values) std::printf(",%u", value);
        std::puts("");
        if (cycle < duration) machine.scheduler.Add(1, Scheduler::EventClass::ARM_ldm_usermode_conflict, 3);
    }
    auto Run() -> std::array<u32, 9> {
        // Normalize away NBA's measured reset phase; never overwrite PPU state.
        while (machine.ppu.mmio.vcount != line || machine.ppu.mmio.dispstat.hblank_flag) {
            machine.scheduler.AddCycles(1);
            if (machine.scheduler.GetTimestampNow() > 2 * 280896) throw std::runtime_error("Could not find scanline origin");
        }
        start = machine.scheduler.GetTimestampNow();
        machine.ppu.mmio.dispcnt.WriteHalf(forced_blank ? 0x80 : 0);
        // Set the comparison byte before enabling its IRQ, avoiding a transient
        // match against reset's comparison value on scanline zero.
        machine.ppu.mmio.dispstat.Write(1, (line + 1) % 228);
        machine.ppu.mmio.dispstat.Write(0, 0x38);
        machine.irq.WriteHalf(0, 0x0207); machine.irq.WriteHalf(4, 1);
        // DMA1: one IWRAM halfword, HBlank trigger, completion IRQ, no repeat.
        const u32 dma[] = {0x03000100, 0x03001000, 0xe0000001};
        for (unsigned b = 0; b < 12; ++b) machine.dma.Write(1, b, dma[b / 4] >> ((b & 3) * 8));
        if (trace) {
            // CPU is idle: borrow its unused conflict slot. Priority 3 observes
            // after device callbacks, before a bus operation at this timestamp returns.
            machine.scheduler.Register(Scheduler::EventClass::ARM_ldm_usermode_conflict, this, &LcdPhaseProbe::Sample);
            machine.scheduler.Add(0, Scheduler::EventClass::ARM_ldm_usermode_conflict, 3);
        }
        while (machine.scheduler.GetTimestampNow() < start + duration) {
            if (machine.dma.IsRunning()) machine.dma.Run();
            else machine.scheduler.AddCycles(1);
        }
        const auto result = Values();
        const bool visible = line < 160;
        if (result[0] != (line + 1) % 228 || result[8] != (visible ? 0x0201U : 0U)
                || result[7] != unsigned(!visible) || result[5] || result[6]) {
            throw std::runtime_error("Natural LCD/DMA completion invariant failed");
        }
        return result;
    }
};

int main() {
    std::puts("line,forced_blank,cycle,vcount,dispstat,irq,ime,cpu_irq,dma_pending,dma_active,dma_enabled,destination");
    for (unsigned line : {0, 159, 160, 227}) for (unsigned blank : {0, 1}) {
        auto control = std::make_unique<LcdPhaseProbe>(line, blank, false);
        const auto expected = control->Run();
        auto observed = std::make_unique<LcdPhaseProbe>(line, blank, true);
        if (observed->Run() != expected) throw std::runtime_error("Observer changed terminal device state");
    }
    return std::fflush(stdout) ? 2 : 0;
}
