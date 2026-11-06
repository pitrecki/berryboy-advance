/* Original asset-free observations of mGBA 0.10.5's disconnected multiplayer port. */
#include <mgba/core/core.h>
#include <mgba/core/config.h>
#include <mgba/core/log.h>
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
    puts("control,elapsed,readback,receive0,receive1,receive2,receive3,send,serial_irq");
    for (unsigned baud=0;baud<4;++baud) for (unsigned busy=0;busy<2;++busy) for (unsigned irq=0;irq<2;++irq) {
        core->reset(core); struct GBA* gba=core->board;
        core->busWrite16(core, 0x04000134, 0);
        core->busWrite16(core, 0x0400012a, 0x55aa);
        unsigned control=0x2000|baud|(busy<<7)|(irq<<14);
        core->busWrite16(core, 0x04000128, control);
        const unsigned samples[]={0,64,8192}; unsigned previous=0;
        for (unsigned i=0;i<3;++i) {
            int32_t pending=gba->cpu->cycles;gba->cpu->cycles=0;
            mTimingTick(&gba->timing, pending+samples[i]-previous);previous=samples[i];
            printf("%u,%u,%u,%u,%u,%u,%u,%u,%u\n",control,samples[i],
                core->busRead16(core,0x04000128),core->busRead16(core,0x04000120),
                core->busRead16(core,0x04000122),core->busRead16(core,0x04000124),
                core->busRead16(core,0x04000126),core->busRead16(core,0x0400012a),
                !!(core->busRead16(core,0x04000202)&0x80));
        }
    }
    mCoreConfigDeinit(&core->config);core->deinit(core);
    return fflush(stdout)?5:0;
}
