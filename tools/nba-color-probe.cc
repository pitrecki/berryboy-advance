/* Original static backdrop-effect captures from an unmodified NBA renderer.
 * Two frames per case: discard the first, inspect the fully settled second.
 * No CPU instructions, commercial ROM or BIOS; original PPU/scheduler run.
 */
#include "nba-reference/probe-machine.hh"
#include <algorithm>
#include <array>

struct FrameCapture final : VideoDevice {
    std::array<u32, 240 * 160> pixels{};
    unsigned frames = 0;
    void Draw(u32* buffer) override {
        std::copy_n(buffer, pixels.size(), pixels.begin());
        ++frames;
    }
};

int main() {
    std::puts("color,effect,coefficient,high_bit,rgb");
    for (unsigned index = 0; index < 33; ++index) {
        const unsigned color = index == 32 ? 0x7e51 : index * 0x421;
        for (unsigned effect : {2, 3}) {
            for (unsigned coefficient = 0; coefficient <= 16; ++coefficient) {
                for (unsigned high_bit : {0, 1}) {
                    auto machine = std::make_unique<ProbeMachine>();
                    auto video = std::make_shared<FrameCapture>();
                    machine->config->video_dev = video;
                    machine->ppu.WritePRAM<u16>(0, color | (high_bit << 15));
                    machine->bus.hw.WriteHalf(0x04000000, 0); // Mode 0, backdrop only, unblank.
                    machine->bus.hw.WriteHalf(0x04000050, 0x20 | (effect << 6));
                    machine->bus.hw.WriteHalf(0x04000054, coefficient);
                    machine->bus.Step(2 * CoreBase::kCyclesPerFrame);
                    if (video->frames != 2 || !std::all_of(video->pixels.begin(), video->pixels.end(),
                            [&](u32 pixel) { return pixel == video->pixels[0]; })) {
                        throw std::runtime_error("Settled backdrop frame is incomplete or nonuniform");
                    }
                    std::printf("0x%04x,%u,%u,%u,0x%06x\n", color, effect, coefficient,
                        high_bit, video->pixels[0] & 0xffffff);
                }
            }
        }
    }
    return std::fflush(stdout) ? 2 : 0;
}
