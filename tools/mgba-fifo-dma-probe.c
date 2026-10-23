/* Original FIFO DMA count-invariance probe for unmodified mGBA 0.10.5.
 * No external assets. Injects an audio FIFO request at cycle zero with a
 * controlled 0..3-cycle service delay; the normal DMA scheduler handles it.
 * A passive callback wrapper observes transfers/completions without changing
 * the library's event deadlines, DMA state or implementation.
 */
#include <mgba/core/core.h>
#include <mgba/core/config.h>
#include <mgba/core/log.h>
#include <mgba/core/timing.h>
#include <mgba/core/version.h>
#include <mgba/internal/arm/arm.h>
#include <mgba/internal/gba/gba.h>
#include <mgba-util/vfs.h>
#include <stdint.h>
#include <stdio.h>
#include <string.h>

#define DMA_BASE 0x040000b0U
#define FIFO_A 0x040000a0U
#define IWRAM_CODE 0x03000000U
#define SOURCE_OFFSET 0x100U
#define WORDS 4U

static void (*dmaCallback)(struct mTiming*, void*, uint32_t);
static int firstTransfer[4], completion[4];
static unsigned transfers[4];

static void observeDma(struct mTiming* timing, void* context, uint32_t late) {
    struct GBA* gba = context;
    int channel = gba->memory.activeDMA;
    if (channel < 0 || channel > 3) return;
    struct GBADMA* dma = &gba->memory.dma[channel];
    if (dma->nextCount & 0xfffff) {
        if (!transfers[channel]++) firstTransfer[channel] = mTimingCurrentTime(timing);
    } else {
        completion[channel] = mTimingCurrentTime(timing);
    }
    dmaCallback(timing, context, late);
}

static void quiet(struct mLogger* logger, int category, enum mLogLevel level,
        const char* format, va_list args) {
    (void)logger; (void)category; (void)level; (void)format; (void)args;
}

int main(void) {
    if (strcmp(projectVersion, "0.10.5")) return 2;
    struct mLogger logger = {.log = quiet};
    mLogSetDefaultLogger(&logger);
    struct mCore* core = mCoreCreate(mPLATFORM_GBA);
    if (!core || !core->init(core)) return 3;
    mCoreConfigInit(&core->config, NULL);
    mCoreConfigSetDefaultIntValue(&core->config, "useBios", 0);
    mCoreConfigSetDefaultIntValue(&core->config, "skipBios", 1);
    mCoreLoadForeignConfig(core, &core->config);
    uint8_t image[512] = {0};
    for (unsigned i = 0; i < 16; ++i) image[SOURCE_OFFSET + i] = i + 1;
    struct VFile* rom = VFileMemChunk(image, sizeof(image));
    if (!rom || !core->loadROM(core, rom)) return 4;
    const uint32_t regions[] = {0x03000000, 0x02000000, 0x08000000};
    const unsigned counts[] = {0, 1, 2, 3, 4, 5, 8, 0x3fff};
    puts("source,channels,count,late,first1,complete1,first2,complete2,after,words1,words2,r0");
    for (unsigned r = 0; r < sizeof(regions) / sizeof(regions[0]); ++r) {
        for (unsigned channels = 1; channels <= 2; ++channels) {
            for (unsigned c = 0; c < sizeof(counts) / sizeof(counts[0]); ++c) {
                for (unsigned late = 0; late < 4; ++late) {
                    core->reset(core);
                    memset(transfers, 0, sizeof(transfers));
                    for (unsigned i = 0; i < 4; ++i) firstTransfer[i] = completion[i] = -1;
                    for (unsigned offset = 0; offset < WORDS * 4; offset += 4) {
                        core->busWrite32(core, 0x03000100 + offset, 0x11110000 + offset);
                        core->busWrite32(core, 0x02000100 + offset, 0x22220000 + offset);
                    }
                    core->rawWrite16(core, IWRAM_CODE, -1, 0x3001); /* ADD r0,#1. */
                    core->rawWrite16(core, IWRAM_CODE + 2, -1, 0x46c0); /* NOP. */
                    uint32_t status = 0x3f, pc = IWRAM_CODE, zero = 0;
                    if (!core->writeRegister(core, "cpsr", &status)
                            || !core->writeRegister(core, "pc", &pc)
                            || !core->writeRegister(core, "r0", &zero)) return 5;
                    core->busWrite16(core, 0x04000084, 0x80); /* Master sound enable. */
                    core->busWrite16(core, 0x04000082, 0x210c); /* FIFO A right, B left, full volume. */
                    struct GBA* gba = core->board;
                    dmaCallback = gba->memory.dmaEvent.callback;
                    gba->memory.dmaEvent.callback = observeDma;
                    for (unsigned channel = 1; channel <= channels; ++channel) {
                        uint32_t base = DMA_BASE + channel * 12;
                        core->busWrite32(core, base, regions[r] + SOURCE_OFFSET);
                        core->busWrite32(core, base + 4, FIFO_A + (channel - 1) * 4);
                        core->busWrite16(core, base + 8, counts[c]);
                        core->busWrite16(core, base + 10, 0xb640);
                    }
                    struct ARMCore* cpu = core->cpu;
                    cpu->cycles = late;
                    for (unsigned channel = 1; channel <= channels; ++channel) {
                        GBAAudioSampleFIFO(&gba->audio, channel - 1, late);
                    }
                    core->step(core);
                    if (cpu->gprs[0] != 1) return 6;
                    for (unsigned channel = 1; channel <= channels; ++channel) {
                        if (transfers[channel] != WORDS || completion[channel] < 0
                                || gba->memory.dma[channel].nextSource != regions[r] + SOURCE_OFFSET + WORDS * 4) return 7;
                    }
                    printf("0x%08x,%u,%u,%u,%d,%d,%d,%d,%u,%u,%u,%u\n",
                        regions[r], channels, counts[c], late, firstTransfer[1], completion[1],
                        firstTransfer[2], completion[2], (unsigned)mTimingCurrentTime(core->timing),
                        transfers[1], transfers[2], cpu->gprs[0]);
                    gba->memory.dmaEvent.callback = dmaCallback;
                }
            }
        }
    }
    mCoreConfigDeinit(&core->config);
    core->deinit(core);
    return fflush(stdout) ? 8 : 0;
}
