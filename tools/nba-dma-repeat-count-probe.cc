/* Original, asset-free repeated-DMA count observation against unmodified NBA.
 * Service starts after activation; request latency is outside this experiment.
 */
#include "nba-reference/probe-machine.hh"

struct RepeatProbe {
    ProbeMachine machine;
    unsigned channel, width, count, write_stage;

    RepeatProbe(unsigned c, unsigned w, unsigned n, unsigned stage)
        : channel(c), width(w), count(n), write_stage(stage) {}

    void WriteCount() {
        machine.dma.Write(channel, 8, count);
        machine.dma.Write(channel, 9, count >> 8);
    }

    void Run() {
        // Fixed source/destination keep even a maximum-length burst in IWRAM.
        const u32 control = width == 4 ? 0xe740 : 0xe340;
        const u32 values[] = {0x03000100, 0x03001000, (control << 16) | 2};
        for (unsigned offset = 0; offset < 12; ++offset) {
            machine.dma.Write(channel, offset, values[offset / 4] >> ((offset & 3) * 8));
        }
        if (write_stage == 0) WriteCount();
        if (write_stage == 1) {
            // First write completes at service+3; update during the next read.
            machine.scheduler.Register(Scheduler::EventClass::ARM_ldm_usermode_conflict,
                                       this, &RepeatProbe::WriteCount);
        }
        for (unsigned burst = 0; burst < 3; ++burst) {
            machine.irq.WriteHalf(2, 0xffff);
            machine.dma.Request(DMA::Occasion::HBlank);
            machine.scheduler.AddCycles(2);
            if (!machine.dma.IsRunning()) throw std::runtime_error("DMA did not activate");
            if (write_stage == 1 && burst == 0) {
                machine.scheduler.Add(4, Scheduler::EventClass::ARM_ldm_usermode_conflict, 3);
            }
            const auto cycles = machine.dma.Run();
            auto state = std::make_unique<SaveState>();
            machine.dma.CopyState(*state);
            const auto* data = machine.bus.memory.iram.data() + 0x1000;
            u32 value = 0;
            for (unsigned b = 0; b < width; ++b) value |= u32(data[b]) << (b * 8);
            std::printf("%u,%u,%u,%u,%u,%d,%u,0x%08x,%u\n", channel, width,
                count, write_stage, burst, cycles, state->dma.channels[channel].latch.length,
                value, machine.irq.ReadHalf(2));
            if (write_stage == 2 && burst == 0) WriteCount();
        }
    }
};

int main() {
    std::puts("channel,width,count,write_stage,burst,cycles,reloaded_count,value,irq");
    for (unsigned channel : {0, 1, 2, 3})
        for (unsigned width : {2, 4})
            for (unsigned count : {1, 3, 0, 0x4002})
                for (unsigned stage : {0, 1, 2}) {
                    auto probe = std::make_unique<RepeatProbe>(channel, width, count, stage);
                    probe->Run();
                }
    return std::fflush(stdout) ? 2 : 0;
}
