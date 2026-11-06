/* Asset-free natural LCD/DMA/IRQ observations using unmodified mGBA 0.10.5.
 * Drive devices one cycle at a time with the CPU idle and IRQ acceptance masked.
 * IRQ scheduling is observed separately from NBA's exposed CPU IRQ input line.
 */
#include <mgba/core/core.h>
#include <mgba/core/config.h>
#include <mgba/core/log.h>
#include <mgba/core/version.h>
#include <mgba/internal/arm/arm.h>
#include <mgba/internal/gba/gba.h>
#include <mgba/internal/gba/io.h>
#include <mgba-util/vfs.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#define DURATION 1248U
#define FIELDS 9U

struct Probe {
    struct GBA* gba;
    struct mTimingEvent observer;
    unsigned line, blank, start, samples;
};

static void require(int condition, const char* message) {
    if (!condition) { fprintf(stderr, "%s\n", message); exit(6); }
}

static void quiet(struct mLogger* logger, int category, enum mLogLevel level, const char* format, va_list args) {
    (void)logger; (void)category; (void)level; (void)format; (void)args;
}
static void values(struct Probe* p, unsigned out[FIELDS]) {
    struct GBA* g = p->gba;
    out[0] = g->video.vcount;
    out[1] = g->memory.io[REG_DISPSTAT >> 1];
    out[2] = g->memory.io[REG_IF >> 1];
    out[3] = g->memory.io[REG_IME >> 1];
    out[4] = mTimingIsScheduled(&g->timing, &g->irqEvent);
    out[5] = mTimingIsScheduled(&g->timing, &g->memory.dmaEvent);
    out[6] = g->cpuBlocked;
    out[7] = !!GBADMARegisterIsEnable(g->memory.dma[1].reg);
    const uint8_t* iwram = (const uint8_t*)g->memory.iwram;
    out[8] = iwram[0x1000] | ((unsigned)iwram[0x1001] << 8);
}
static void sample(struct mTiming* timing, void* context, uint32_t late) {
    struct Probe* p = context; unsigned v[FIELDS];
    require(!late, "Observer dispatched late");
    unsigned cycle = mTimingCurrentTime(timing) - p->start;
    values(p, v); printf("%u,%u,%u", p->line, p->blank, cycle);
    for (unsigned i = 0; i < FIELDS; ++i) printf(",%u", v[i]);
    puts(""); ++p->samples;
    if (cycle < DURATION) mTimingSchedule(timing, &p->observer, 1);
}
static void tick(struct Probe* p) {
    require(p->gba->cpu->cycles == 0, "Unexpected CPU work before device tick");
    mTimingTick(&p->gba->timing, 1);
    require(p->gba->cpu->cycles == 0, "Device callback executed CPU work");
}
static void run(struct mCore* core, unsigned line, unsigned blank, int observed, unsigned terminal[FIELDS]) {
    core->reset(core);
    struct Probe p = {.gba = core->board, .line = line, .blank = blank};
    // The device-only experiment cannot enter a CPU handler or execute opcodes.
    p.gba->cpu->cpsr.i = 1;
    // Account for reset's pipeline refill before finding a natural line edge,
    // using the same relative-to-master handoff as the normal event driver.
    int32_t resetCycles = p.gba->cpu->cycles; p.gba->cpu->cycles = 0;
    mTimingTick(&p.gba->timing, resetCycles);
    while (p.gba->video.vcount != (int)line || (p.gba->memory.io[REG_DISPSTAT >> 1] & 2)) {
        tick(&p);
        require(mTimingCurrentTime(&p.gba->timing) <= 2 * 280896, "Could not find scanline origin");
    }
    p.start = mTimingCurrentTime(&p.gba->timing);
    core->busWrite16(core, 0x04000000, blank ? 0x80 : 0);
    core->busWrite16(core, 0x04000004, ((line + 1) % 228) << 8);
    core->busWrite16(core, 0x04000004, (((line + 1) % 228) << 8) | 0x38);
    core->busWrite16(core, 0x04000200, 0x0207); core->busWrite16(core, 0x04000208, 1);
    core->busWrite16(core, 0x03000100, 0x0201);
    core->busWrite16(core, 0x03001000, 0); // Core reset can retain RAM from the previous case.
    core->busWrite32(core, 0x040000bc, 0x03000100); core->busWrite32(core, 0x040000c0, 0x03001000);
    core->busWrite16(core, 0x040000c4, 1); core->busWrite16(core, 0x040000c6, 0xe000);
    if (observed) {
        // Highest priority number observes after existing device callbacks at
        // this timestamp. None of their callbacks, contexts or priorities change.
        p.observer = (struct mTimingEvent){.context=&p, .callback=sample, .name="LCD observer", .priority=UINT_MAX};
        mTimingSchedule(&p.gba->timing, &p.observer, 0); mTimingTick(&p.gba->timing, 0);
    }
    while ((unsigned)mTimingCurrentTime(&p.gba->timing) < p.start + DURATION) tick(&p);
    values(&p, terminal);
    int valid = terminal[0] == (line + 1) % 228 && terminal[8] == (line < 160 ? 0x0201U : 0U)
            && terminal[7] == (line >= 160) && !terminal[5] && !terminal[6]
            && (!observed || p.samples == DURATION + 1);
    if (!valid) {
        fprintf(stderr, "line=%u blank=%u observed=%d samples=%u terminal:", line, blank, observed, p.samples);
        for (unsigned i=0;i<FIELDS;++i) fprintf(stderr, " %u", terminal[i]);
        fputc('\n', stderr);
    }
    require(valid, "Natural LCD/DMA completion invariant failed");
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
    puts("line,forced_blank,cycle,vcount,dispstat,irq,ime,irq_scheduled,dma_scheduled,cpu_blocked,dma_enabled,destination");
    const unsigned lines[] = {0, 159, 160, 227};
    for (unsigned i = 0; i < 4; ++i) for (unsigned blank = 0; blank <= 1; ++blank) {
        unsigned control[FIELDS], observed[FIELDS];
        run(core, lines[i], blank, 0, control); run(core, lines[i], blank, 1, observed);
        require(!memcmp(control, observed, sizeof(control)), "Observer changed terminal device state");
    }
    mCoreConfigDeinit(&core->config); core->deinit(core);
    return fflush(stdout) ? 5 : 0;
}
