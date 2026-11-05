/* Original release-boundary probe; readiness is normalized after activation. */
#include "nba-reference/probe-machine.hh"

struct ReleaseProbe {
    ProbeMachine machine;

    void RequestFifos() {
        machine.dma.Request(DMA::Occasion::FIFO0);
        machine.dma.Request(DMA::Occasion::FIFO1);
    }

    void Run() {
        machine.Configure(1, 0x03000100, 4);
        machine.Configure(2, 0x03000100, 4);
        const u32 values[] = {0x02000100, 0x03000200, 0x80000001};
        for (unsigned offset = 0; offset < 12; ++offset) {
            machine.dma.Write(3, offset, values[offset / 4] >> ((offset & 3) * 8));
        }
        // FIFO requests at 6 activate at 8, during DMA3's release cycle.
        // BerryBoy must inject READINESS at 8, not requests at 6: this
        // comparison deliberately excludes its still unqualified activation.
        machine.scheduler.Register(Scheduler::EventClass::ARM_ldm_usermode_conflict, this, &ReleaseProbe::RequestFifos);
        machine.scheduler.Add(6, Scheduler::EventClass::ARM_ldm_usermode_conflict);
        machine.scheduler.AddCycles(2);
        if (!machine.dma.IsRunning()) throw std::runtime_error("Primary DMA did not activate");
        const auto first = machine.dma.Run();
        const auto fifo_ready = machine.dma.IsRunning();
        if (!fifo_ready) throw std::runtime_error("FIFO activation missed release boundary");
        const auto second = machine.dma.Run();
        if (machine.dma.IsRunning() || first != 6 || second != 18) throw std::runtime_error("Unexpected ownership split");
        std::puts("first_ownership,next_ownership,total_ownership,fifo_ready_after_release");
        std::printf("%d,%d,%d,%u\n", first, second, first + second, unsigned(fifo_ready));
    }
};

int main() {
    auto probe = std::make_unique<ReleaseProbe>();
    probe->Run();
    return std::fflush(stdout) ? 2 : 0;
}
