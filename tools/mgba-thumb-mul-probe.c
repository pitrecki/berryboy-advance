/* Original asset-free Thumb timing cases for an unmodified mGBA 0.10.5 library.
 * No ROM or BIOS files are loaded. Run before comparing the emulator regressions.
 */
#include <mgba/core/core.h>
#include <mgba/core/config.h>
#include <mgba/core/timing.h>
#include <mgba/core/version.h>
#include <mgba/internal/arm/arm.h>
#include <stdint.h>
#include <stdio.h>
#include <string.h>

#define IWRAM_BASE 0x03000000U
#define THUMB_SYSTEM_CARRY_OVERFLOW 0x3000003fU

int main(void) {
    if (strcmp(projectVersion, "0.10.5")) return 2;
    const int32_t cases[][2] = {
        {0,2147483647}, {255,305419896}, {256,1}, {65535,1},
        {65536,224}, {16777215,1}, {16777216,1}, {2147483647,1},
        {-1,305419896}, {-256,5}, {-257,1}, {-65536,7},
        {-65537,3}, {-16777216,2}, {-16777217,1}, {597275,224}
    };
    struct mCore* core = mCoreCreate(mPLATFORM_GBA);
    if (!core || !core->init(core)) return 3;
    mCoreConfigInit(&core->config, NULL);
    mCoreConfigSetDefaultIntValue(&core->config, "useBios", 0);
    mCoreConfigSetDefaultIntValue(&core->config, "skipBios", 1);
    mCoreLoadForeignConfig(core, &core->config);
    puts("destination,source,cycles,result,cpsr");
    for (unsigned i = 0; i < sizeof(cases)/sizeof(cases[0]); ++i) {
        core->reset(core);
        core->rawWrite16(core, IWRAM_BASE, -1, 0x4348); /* MUL r0,r1. */
        core->rawWrite16(core, IWRAM_BASE + 2, -1, 0x46c0); /* NOP. */
        uint32_t status = THUMB_SYSTEM_CARRY_OVERFLOW, pc = IWRAM_BASE;
        if (!core->writeRegister(core, "cpsr", &status) || !core->writeRegister(core, "pc", &pc)
                || !core->writeRegister(core, "r0", &cases[i][0]) || !core->writeRegister(core, "r1", &cases[i][1])) return 4;
        uint32_t start = mTimingCurrentTime(core->timing);
        core->step(core);
        struct ARMCore* cpu = core->cpu;
        printf("%d,%d,%u,%u,%u\n", cases[i][0], cases[i][1],
            (uint32_t)mTimingCurrentTime(core->timing) - start, cpu->gprs[0], cpu->cpsr.packed);
    }
    mCoreConfigDeinit(&core->config); core->deinit(core);
    return fflush(stdout) ? 5 : 0;
}
