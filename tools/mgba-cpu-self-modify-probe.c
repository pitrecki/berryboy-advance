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
    const int extended = argc == 2 && !strcmp(argv[1], "--extended-memory");
    if ((argc != 1 && !extended) || strcmp(projectVersion, "0.10.5")) return 2;
    struct mLogger logger = {.log = quiet}; mLogSetDefaultLogger(&logger);
    puts(extended ? "mode,code,patch_base,store_width,distance,stage,cycles,pc,cpsr,r0,patched"
                  : "mode,code,store_width,distance,stage,cycles,pc,cpsr,r0,patched");
    const unsigned locations[][2] = {
        {0x02000100, 0x02000100}, {0x03000100, 0x03000100},
        {0x02000100, 0x02040100}, {0x02040100, 0x02000100}, {0x02fc0100, 0x02000100},
        {0x03000100, 0x03008100}, {0x03008100, 0x03000100}, {0x03ff8100, 0x03000100},
        {0x06000100, 0x06020100}, {0x06020100, 0x06000100},
        {0x06010100, 0x06018100}, {0x06018100, 0x06010100},
        {0x05000100, 0x05000500}, {0x05000500, 0x05000100},
        {0x07000100, 0x07000500}, {0x07000500, 0x07000100}
    };
    for (unsigned thumb = 0; thumb < 2; ++thumb)
      for (unsigned location = 0; location < (extended ? 16U : 2U); ++location)
        for (unsigned store = 1; store <= 4; store *= 2)
          for (unsigned distance = 1; distance <= 4; ++distance) {
            const unsigned code = locations[location][0], patch_base = locations[location][1];
            if ((code >> 24) >= 5 && store == 1) continue;
            struct mCore* core = mCoreCreate(mPLATFORM_GBA);
            if (!core || !core->init(core)) return 3;
            mCoreConfigInit(&core->config, NULL);
            mCoreConfigSetDefaultIntValue(&core->config, "useBios", 0);
            mCoreConfigSetDefaultIntValue(&core->config, "skipBios", 1);
            mCoreLoadForeignConfig(core, &core->config);
            unsigned char image[512] = {0};
            if (!core->loadROM(core, VFileMemChunk(image, sizeof(image)))) return 4;
            core->reset(core);
            if (extended) core->busWrite16(core, 0x04000000, 0x80);
            const unsigned width = thumb ? 2 : 4, target = code + distance * width;
            const unsigned patch_address = patch_base + distance * width;
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
            unsigned destination = store == 4 ? patch_address & ~3U : patch_address;
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
                printf("%s,0x%08x,", thumb ? "thumb" : "arm", code);
                if (extended) printf("0x%08x,", patch_base);
                printf("%u,%u,%u,%u,0x%08x,0x%08x,%u,0x%08x\n", store, distance, stage,
                    (unsigned) mTimingCurrentTime(core->timing) - start,
                    (unsigned) cpu->gprs[15] - width, cpu->cpsr.packed, cpu->gprs[0], patched);
            }
            mCoreConfigDeinit(&core->config); core->deinit(core);
          }
    return fflush(stdout) ? 7 : 0;
}
