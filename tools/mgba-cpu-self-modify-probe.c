/* Self-modifying RAM programs, linked to unmodified mGBA 0.10.5. */
#include <mgba/core/core.h>
#include <mgba/core/config.h>
#include <mgba/core/log.h>
#include <mgba/core/timing.h>
#include <mgba/core/version.h>
#include <mgba/internal/arm/arm.h>
#include <mgba-util/vfs.h>
#include <stdio.h>
#include <string.h>

static void quiet(struct mLogger* logger, int category, enum mLogLevel level, const char* format, va_list args) {
    (void)logger; (void)category; (void)level; (void)format; (void)args;
}
static void put(struct mCore* core, unsigned address, unsigned value, unsigned width) {
    if (width == 2) core->busWrite16(core, address, value); else core->busWrite32(core, address, value);
}
int main(int argc, char** argv) {
    (void)argv;
    if (argc != 1 || strcmp(projectVersion, "0.10.5")) return 2;
    struct mLogger logger = {.log = quiet}; mLogSetDefaultLogger(&logger);
    puts("mode,code,store_width,distance,stage,cycles,pc,cpsr,r0,patched");
    for (unsigned thumb = 0; thumb < 2; ++thumb)
      for (unsigned region = 2; region <= 3; ++region)
        for (unsigned store = 1; store <= 4; store *= 2)
          for (unsigned distance = 1; distance <= 4; ++distance) {
            struct mCore* core = mCoreCreate(mPLATFORM_GBA);
            if (!core || !core->init(core)) return 3;
            mCoreConfigInit(&core->config, NULL);
            mCoreConfigSetDefaultIntValue(&core->config, "useBios", 0);
            mCoreConfigSetDefaultIntValue(&core->config, "skipBios", 1);
            mCoreLoadForeignConfig(core, &core->config);
            unsigned char image[512] = {0};
            if (!core->loadROM(core, VFileMemChunk(image, sizeof(image)))) return 4;
            core->reset(core);
            const unsigned width = thumb ? 2 : 4, code = (region << 24) + 0x100, target = code + distance * width;
            put(core, 0x03000080, 0xe12fff14, 4);
            put(core, code, thumb ? (store == 1 ? 0x7011 : store == 2 ? 0x8011 : 0x6011)
                                 : (store == 1 ? 0xe5c21000 : store == 2 ? 0xe1c210b0 : 0xe5821000), width);
            for (unsigned i = 1; i <= 4; ++i) put(core, code + i * width, thumb ? 0x2000 | i : 0xe3b00000 | i, width);
            put(core, code + 5 * width, thumb ? 0x4718 : 0xe12fff13, width);
            unsigned patch = thumb ? 0x2009 : 0xe3b00009;
            if (thumb && store == 4) patch = (target & 2)
                ? 0x20090000 | (distance == 1 ? 0x6011 : 0x2000 | (distance - 1))
                : patch | (distance == 4 ? 0x4718 : 0x2000 | (distance + 1)) << 16;
            unsigned status = 0xdf, pc = 0x03000080, branch = target | thumb, entry = code | thumb;
            unsigned destination = store == 4 ? target & ~3U : target;
            if (!core->writeRegister(core, "cpsr", &status) || !core->writeRegister(core, "r1", &patch)
                || !core->writeRegister(core, "r2", &destination) || !core->writeRegister(core, "r3", &branch)
                || !core->writeRegister(core, "r4", &entry) || !core->writeRegister(core, "pc", &pc)) return 5;
            core->step(core);
            struct ARMCore* cpu = core->cpu;
            if ((unsigned) cpu->gprs[15] - width != code || cpu->executionMode != (thumb ? MODE_THUMB : MODE_ARM)) return 6;
            const unsigned start = mTimingCurrentTime(core->timing);
            for (unsigned stage = 1; stage <= 7; ++stage) {
                core->step(core);
                const unsigned patched = thumb ? core->busRead16(core, target) : core->busRead32(core, target);
                printf("%s,0x%08x,%u,%u,%u,%u,0x%08x,0x%08x,%u,0x%08x\n",
                    thumb ? "thumb" : "arm", code, store, distance, stage,
                    (unsigned) mTimingCurrentTime(core->timing) - start,
                    (unsigned) cpu->gprs[15] - width, cpu->cpsr.packed, cpu->gprs[0], patched);
            }
            mCoreConfigDeinit(&core->config); core->deinit(core);
          }
    return fflush(stdout) ? 7 : 0;
}
