/* Original asset-free comparison using unmodified mGBA 0.10.5.
 * Nonzero bus/prefetch values guard against mistaking a zero bus for reserved-lane behavior.
 */
#include <mgba/core/core.h>
#include <mgba/core/config.h>
#include <mgba/core/log.h>
#include <mgba/core/version.h>
#include <mgba/internal/arm/arm.h>
#include <mgba/internal/gba/gba.h>
#include <mgba-util/vfs.h>
#include <stdio.h>
#include <string.h>

static void quiet(struct mLogger* logger, int category, enum mLogLevel level, const char* format, va_list args) {
    (void)logger; (void)category; (void)level; (void)format; (void)args;
}
int main(void) {
    if (strcmp(projectVersion, "0.10.5")) return 2;
    struct mLogger logger = {.log=quiet}; mLogSetDefaultLogger(&logger);
    struct mCore* core = mCoreCreate(mPLATFORM_GBA);
    if (!core || !core->init(core)) return 3;
    mCoreConfigInit(&core->config, NULL);
    mCoreConfigSetDefaultIntValue(&core->config, "useBios", 0);
    mCoreConfigSetDefaultIntValue(&core->config, "skipBios", 1); mCoreLoadForeignConfig(core, &core->config);
    uint8_t image[512] = {0}; struct VFile* rom = VFileMemChunk(image, sizeof(image));
    if (!rom || !core->loadROM(core, rom)) return 4;
    puts("width,value,byte0,byte1,upper_halfword,word,ie,irq");
    const unsigned values[] = {0, 1, 2, 0xffffffff};
    for (unsigned width = 1; width <= 4; width *= 2) for (unsigned i = 0; i < 4; ++i) {
        unsigned value = values[i]; core->reset(core);
        struct GBA* gba = core->board;
        core->busWrite16(core, 0x04000200, 0x0200); GBARaiseIRQ(gba, GBA_IRQ_DMA1, 0);
        if (width == 1) core->busWrite8(core, 0x04000208, value);
        else if (width == 2) core->busWrite16(core, 0x04000208, value);
        else core->busWrite32(core, 0x04000208, value);
        core->busWrite8(core, 0x04000209, 0xaa); core->busWrite16(core, 0x0400020a, 0xbeef);
        gba->bus = 0x89abcdef; gba->cpu->prefetch[0] = 0x12345678; gba->cpu->prefetch[1] = 0x89abcdef;
        printf("%u,%u,%u,%u,%u,%u,%u,%u\n", width, value,
            core->busRead8(core, 0x04000208), core->busRead8(core, 0x04000209),
            core->busRead16(core, 0x0400020a), core->busRead32(core, 0x04000208),
            core->busRead16(core, 0x04000200), core->busRead16(core, 0x04000202));
    }
    mCoreConfigDeinit(&core->config); core->deinit(core);
    return fflush(stdout) ? 5 : 0;
}
