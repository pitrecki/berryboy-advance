/* Asset-free post-IRQ cartridge stream, linked to unmodified mGBA 0.10.5.
 * IRQ entry is explicit setup through ARMRaiseIRQ, not peripheral latency.
 * Default timestamps start after SUBS pc,lr,#4 has retired in each engine.
 */
#include <mgba/core/core.h>
#include <mgba/core/config.h>
#include <mgba/core/log.h>
#include <mgba/core/timing.h>
#include <mgba/core/version.h>
#include <mgba/internal/arm/arm.h>
#include <mgba/internal/gba/gba.h>
#include <mgba-util/vfs.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

static void quiet(struct mLogger* logger, int category, enum mLogLevel level, const char* format, va_list args) {
    (void)logger; (void)category; (void)level; (void)format; (void)args;
}
static void word(unsigned char* bytes, unsigned offset, unsigned value) {
    for (unsigned b = 0; b < 4; b++) bytes[offset + b] = value >> (b * 8);
}

int main(int argc, char** argv) {
    const int full = argc == 2 && !strcmp(argv[1], "--full-trace");
    if ((argc != 1 && !full) || strcmp(projectVersion, "0.10.5")) return 2;
    struct mLogger logger = {.log = quiet}; mLogSetDefaultLogger(&logger);
    puts(full ? "mode,code,prefetch,idle,stage,cycle,pc,cpsr,lr,r0"
              : "mode,code,prefetch,idle,add,cycles_since_return,pc,cpsr,lr,r0");
    for (unsigned thumb = 0; thumb < 2; thumb++) {
        struct mCore* core = mCoreCreate(mPLATFORM_GBA);
        if (!core || !core->init(core)) return 3;
        mCoreConfigInit(&core->config, NULL);
        mCoreConfigSetDefaultIntValue(&core->config, "useBios", 1);
        mCoreConfigSetDefaultIntValue(&core->config, "skipBios", 1);
        mCoreLoadForeignConfig(core, &core->config);
        unsigned char* image = calloc(0x20040, 1);
        if (!image) return 4;
        const unsigned width = thumb ? 2 : 4, add = thumb ? 0x3001 : 0xe2800001;
        for (unsigned i = 0; i < 0x20040; i++) image[i] = add >> ((i % width) * 8);
        struct VFile* rom = VFileMemChunk(image, 0x20040); free(image);
        if (!rom || !core->loadROM(core, rom)) return 5;
        unsigned char bios[0x4000] = {0};
        word(bios, 0x18, 0xe1a00000); word(bios, 0x1c, 0xe25ef004);
        // The synthetic vector deliberately contains NOP instead of a BIOS-identifying branch.
        // Use the core's loader directly; no production instruction/event implementation is patched.
        GBALoadBIOS(core->board, VFileMemChunk(bios, sizeof(bios)));
        if (!((struct GBA*) core->board)->memory.fullBios) return 6;
        const unsigned banks[] = {0x08000000, 0x0a000000, 0x0c000000};
        const unsigned offsets[] = {0x20, 0x20000 - width, 0x20000};
        for (unsigned bank = 0; bank < 3; bank++)
          for (unsigned offset = 0; offset < 3; offset++)
            for (unsigned setup = 0; setup < 3; setup++) {
                core->reset(core);
                unsigned status = 0xa000005f | (thumb ? 0x20 : 0);
                unsigned pc = banks[bank] + offsets[offset] - width, zero = 0;
                core->busWrite16(core, 0x04000204, setup ? 0x4000 : 0);
                if (!core->writeRegister(core, "cpsr", &status) || !core->writeRegister(core, "pc", &pc)) return 7;
                core->step(core);
                core->writeRegister(core, "r0", &zero); core->writeRegister(core, "cpsr", &status);
                struct ARMCore* cpu = core->cpu;
                const unsigned idle = setup == 2 ? 24 : 0;
                cpu->cycles += idle;
                const unsigned start = mTimingCurrentTime(core->timing);
                unsigned returned = 0;
                ARMRaiseIRQ(cpu);
                for (unsigned stage = 1; stage <= 5; stage++) {
                    core->step(core);
                    const unsigned logical = cpu->gprs[15] - (cpu->executionMode == MODE_THUMB ? 2 : 4);
                    if (stage == 1 && (logical != 0x1c || (unsigned) cpu->gprs[14] != pc + width + 4 || cpu->gprs[0])) return 8;
                    if (stage == 2) {
                        if (logical != pc + width || (unsigned) cpu->cpsr.packed != status || cpu->gprs[14] || cpu->gprs[0]) return 9;
                        returned = mTimingCurrentTime(core->timing);
                    }
                    if (full || stage > 2) printf("%s,0x%08x,%u,%u,%u,%u,0x%08x,0x%08x,0x%08x,%u\n",
                        thumb ? "thumb" : "arm", pc + width, setup != 0, idle, full ? stage : stage - 2,
                        (unsigned) mTimingCurrentTime(core->timing) - (full ? start : returned),
                        logical, cpu->cpsr.packed, cpu->gprs[14], cpu->gprs[0]);
                }
            }
        mCoreConfigDeinit(&core->config); core->deinit(core);
    }
    return fflush(stdout) ? 10 : 0;
}
