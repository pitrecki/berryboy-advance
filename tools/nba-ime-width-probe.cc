/* Original, asset-free IME register-width observations from unmodified NBA. */
#include "nba-reference/probe-machine.hh"

int main() {
    std::puts("width,value,byte0,byte1,upper_halfword,word,ie,irq");
    for (unsigned width : {1, 2, 4}) for (u32 value : {0U, 1U, 2U, 0xffffffffU}) {
        auto m = std::make_unique<ProbeMachine>();
        m->irq.WriteHalf(0, 0x0200); m->irq.Raise(IRQ::Source::DMA, 1);
        if (width == 1) m->bus.hw.WriteByte(0x04000208, value);
        else if (width == 2) m->bus.hw.WriteHalf(0x04000208, value);
        else m->bus.hw.WriteWord(0x04000208, value);
        m->bus.hw.WriteByte(0x04000209, 0xaa);
        m->bus.hw.WriteHalf(0x0400020a, 0xbeef);
        m->scheduler.AddCycles(8); // Settle original IRQ publication, not a timing observation.
        std::printf("%u,%u,%u,%u,%u,%u,%u,%u\n", width, value,
            unsigned(m->bus.hw.ReadByte(0x04000208)), unsigned(m->bus.hw.ReadByte(0x04000209)),
            unsigned(m->bus.hw.ReadHalf(0x0400020a)), m->bus.hw.ReadWord(0x04000208),
            unsigned(m->irq.ReadHalf(0)), unsigned(m->irq.ReadHalf(2)));
    }
    return std::fflush(stdout) ? 2 : 0;
}
