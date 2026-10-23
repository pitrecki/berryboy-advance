/* Original DMA source-mode cases for an unmodified mGBA 0.10.5 library.
 * Generates its own 512-byte cartridge; no commercial ROM or BIOS is loaded.
 * Only transfer contents and control readback are qualified, not DMA timing.
 */
#include <mgba/core/core.h>
#include <mgba/core/config.h>
#include <mgba/core/log.h>
#include <mgba/core/version.h>
#include <mgba-util/vfs.h>
#include <stdint.h>
#include <stdio.h>
#include <string.h>

#define DMA_3 0x040000d4U
#define DESTINATION 0x03000100U
#define TRANSFERS 3U

static void quiet(struct mLogger* logger, int category, enum mLogLevel level,
        const char* format, va_list args) {
    (void)logger; (void)category; (void)level; (void)format; (void)args;
}

int main(void) {
    if (strcmp(projectVersion, "0.10.5")) return 2;
    struct mLogger logger = {.log = quiet};
    mLogSetDefaultLogger(&logger);
    uint8_t image[512];
    for (unsigned offset = 0; offset < sizeof(image); offset += 2) {
        uint16_t value = 0x1000 + offset / 2;
        image[offset] = value;
        image[offset + 1] = value >> 8;
    }
    const uint32_t regions[] = {0x02000000, 0x08000000, 0x0a000000, 0x0c000000};
    struct mCore* core = mCoreCreate(mPLATFORM_GBA);
    if (!core || !core->init(core)) return 3;
    mCoreConfigInit(&core->config, NULL);
    mCoreConfigSetDefaultIntValue(&core->config, "useBios", 0);
    mCoreConfigSetDefaultIntValue(&core->config, "skipBios", 1);
    mCoreLoadForeignConfig(core, &core->config);
    struct VFile* rom = VFileMemChunk(image, sizeof(image));
    if (!rom || !core->loadROM(core, rom)) return 4;
    puts("region,width,mode,control,halfwords");
    for (unsigned r = 0; r < sizeof(regions) / sizeof(regions[0]); ++r) {
        for (unsigned width = 2; width <= 4; width += 2) {
            for (unsigned mode = 0; mode < 3; ++mode) {
                core->reset(core);
                for (unsigned offset = 0; offset < sizeof(image); offset += 2) {
                    core->busWrite16(core, 0x02000000 + offset, 0x1000 + offset / 2);
                }
                core->rawWrite32(core, 0x03000000, -1, 0xeafffffe); /* B . */
                uint32_t pc = 0x03000000;
                if (!core->writeRegister(core, "pc", &pc)) return 5;
                unsigned control = 0x8000 | (width == 4 ? 0x400 : 0) | (mode << 7);
                core->busWrite32(core, DMA_3, regions[r] + 0x100);
                core->busWrite32(core, DMA_3 + 4, DESTINATION);
                core->busWrite16(core, DMA_3 + 8, TRANSFERS);
                core->busWrite16(core, DMA_3 + 10, control);
                unsigned steps = 0;
                while (core->busRead16(core, DMA_3 + 10) & 0x8000) {
                    if (++steps > 100) return 6;
                    core->step(core);
                }
                printf("0x%08x,%u,%u,0x%04x,", regions[r], width, mode,
                    core->busRead16(core, DMA_3 + 10));
                for (unsigned i = 0; i < TRANSFERS * width / 2; ++i) {
                    printf("%s%04x", i ? " " : "", core->busRead16(core, DESTINATION + i * 2));
                }
                puts("");
            }
        }
    }
    mCoreConfigDeinit(&core->config);
    core->deinit(core);
    return fflush(stdout) ? 7 : 0;
}
