/* Original, asset-free DMA phase probe using the pinned, unmodified NBA core.
 * Observe already activated HBlank DMA; activation/CPU latency is excluded.
 */
#include "nba-reference/probe-machine.hh"

struct PhaseProbe {
    ProbeMachine machine;
    u32 source, destination;
    unsigned width, channels, count, delay;
    u64 start = 0;
    static constexpr unsigned samples = 200;

    PhaseProbe(u32 src, u32 dst, unsigned size, unsigned n, unsigned length, unsigned late)
        : source(src), destination(dst), width(size), channels(n), count(length), delay(late) {}

    auto Destination(unsigned channel, unsigned offset) -> u32 {
        const auto address = destination + (channel - 1) * 0x100 + offset;
        const auto* bytes = destination >> 24 == 2 ? machine.bus.memory.wram.data() : machine.bus.memory.iram.data();
        const auto index = address & (destination >> 24 == 2 ? 0x3ffff : 0x7fff);
        u32 value = 0;
        for (unsigned i = 0; i < width; ++i) value |= u32(bytes[index + i]) << (i * 8);
        return value;
    }

    void Sample() {
        unsigned enabled = 0;
        for (unsigned c = 1; c <= channels; ++c) {
            if (machine.dma.Read(c, 11) & 0x80) enabled |= 1 << c;
        }
        const auto cycle = machine.scheduler.GetTimestampNow() - start;
        std::printf("0x%08x,0x%08x,%u,%u,%u,%u,%llu,0x%08x,0x%08x,0x%08x,0x%08x,%u,%u\n",
            source, destination, width, channels, count, delay, (unsigned long long)cycle,
            Destination(1, 0), Destination(1, (count - 1) * width),
            channels == 2 ? Destination(2, 0) : 0,
            channels == 2 ? Destination(2, (count - 1) * width) : 0,
            enabled, machine.irq.ReadHalf(2));
        if (cycle < samples) machine.scheduler.Add(1, Scheduler::EventClass::ARM_ldm_usermode_conflict, 3);
    }

    void Run() {
        for (unsigned c = 1; c <= channels; ++c) {
            const u32 control = width == 4 ? 0xe400 : 0xe000; // HBlank, completion IRQ.
            const u32 values[] = {source + 0x100, destination + (c - 1) * 0x100, (control << 16) | count};
            for (unsigned offset = 0; offset < 12; ++offset) {
                machine.dma.Write(c, offset, values[offset / 4] >> ((offset & 3) * 8));
            }
        }
        machine.dma.Request(DMA::Occasion::HBlank);
        machine.scheduler.AddCycles(delay);
        if (!machine.dma.IsRunning()) throw std::runtime_error("DMA not activated");
        start = machine.scheduler.GetTimestampNow();
        // CPU never executes: borrow its otherwise unused conflict-event slot
        // for the observer. Priority 3 samples AFTER queued device callbacks,
        // before the bus operation finishing at that timestamp can return.
        machine.scheduler.Register(Scheduler::EventClass::ARM_ldm_usermode_conflict, this, &PhaseProbe::Sample);
        machine.scheduler.Add(1, Scheduler::EventClass::ARM_ldm_usermode_conflict, 3);
        const auto elapsed = machine.dma.Run();
        if (machine.dma.IsRunning() || elapsed >= int(samples)) throw std::runtime_error("DMA did not finish");
        machine.scheduler.AddCycles(samples - elapsed);
        const auto expected = width == 4 ? 0x04030201U : 0x0201U;
        if (Destination(1, 0) != expected || (channels == 2 && Destination(2, 0) != expected)) {
            throw std::runtime_error("Transfer content mismatch");
        }
    }
};

int main() {
    std::puts("source,destination,width,channels,count,service_delay,cycle,first1,last1,first2,last2,enabled,irq");
    for (u32 source : {0x03000000, 0x02000000, 0x08000000})
        for (u32 destination : {0x03001000, 0x02001000})
            for (unsigned width : {2, 4})
                for (unsigned channels : {1, 2})
                    for (unsigned count : {1, 2, 4})
                        for (unsigned delay : {2, 3}) {
                            auto probe = std::make_unique<PhaseProbe>(source, destination, width, channels, count, delay);
                            probe->Run();
                        }
    return std::fflush(stdout) ? 2 : 0;
}
