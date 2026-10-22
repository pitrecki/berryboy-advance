/* Standalone mGBA 0.10.5 diagnostic; compile/link against a local libmgba build.
 * Requires the AMTE USA ROM and real BIOS locally. No assets are embedded.
 * This checks specific bus observations, not full video/audio correctness.
 */
#include <mgba/core/core.h>
#include <mgba/core/config.h>
#include <mgba/core/log.h>
#include <mgba/core/timing.h>
#include <mgba/internal/arm/arm.h>
#include <mgba/internal/gba/gba.h>
#include <mgba-util/vfs.h>
#include <fcntl.h>
#include <stdio.h>

static void quiet(struct mLogger* logger, int category, enum mLogLevel level,
                  const char* format, va_list args) {
    (void)logger; (void)category; (void)level; (void)format; (void)args;
}

int main(int argc, char** argv) {
    if (argc != 3) {
        fprintf(stderr, "Usage: mgba-fusion-probe <Metroid Fusion USA.gba> <gba_bios.bin>\n");
        return 2;
    }
    struct mLogger logger = { .log = quiet };
    mLogSetDefaultLogger(&logger);
    struct mCore* core = mCoreFind(argv[1]);
    if (!core || !core->init(core)) return 3;
    mCoreConfigInit(&core->config, NULL);
    mCoreConfigSetDefaultIntValue(&core->config, "useBios", 1);
    mCoreConfigSetDefaultIntValue(&core->config, "skipBios", 0);
    mCoreConfigSetDefaultIntValue(&core->config, "gba.audioHle", 0);
    mCoreLoadConfig(core);
    if (!mCoreLoadFile(core, argv[1])) return 4;
    struct VFile* bios = VFileOpen(argv[2], O_RDONLY);
    if (!bios || !core->loadBIOS(core, bios, 0)) return 5;
    core->reset(core);
    struct ARMCore* cpu = core->cpu;
    struct GBA* gba = core->board;
    int nextPress = 360, release = -1, reads = 0, setups = 0;
    const uint32_t cyclesPerFrame = 280896;
    /* CurrentTime includes master+CPU cycles even without USE_DEBUGGERS.
     * This bounded run stays below its 32-bit wrap; GlobalTime in this mGBA
     * version only accumulates full guest time when debugging is enabled.
     */
    while ((uint32_t)mTimingCurrentTime(core->timing) < 1200U * cyclesPerFrame) {
        uint32_t cycles = mTimingCurrentTime(core->timing);
        if (nextPress < 1200 && cycles >= (uint32_t)nextPress * cyclesPerFrame) {
            core->setKeys(core, (nextPress / 60) % 2 == 0 ? 8 : 1);
            release = nextPress + 3;
            nextPress += 60;
        }
        if (release >= 0 && cycles >= (uint32_t)release * cyclesPerFrame) {
            core->setKeys(core, 0);
            release = -1;
        }
        uint32_t pc = cpu->gprs[15] - (cpu->executionMode == MODE_THUMB ? 2 : 4);
        if (pc == 0x0300367c && cpu->gprs[3] == 0 && setups++ == 0) {
            printf("zero-waveform bios-latch=%08x read-0c=%08x\n",
                   gba->memory.biosPrefetch, core->busRead32(core, 12));
        }
        uint32_t address = cpu->gprs[5] + cpu->gprs[9];
        core->step(core);
        if (pc == 0x030037ac && address >= 0x4000 && address < 0x10000 && reads++ < 4) {
            printf("unmapped-read address=%08x result=%08x\n", address, cpu->gprs[3]);
        }
    }
    printf("completed frames=%u zero-waveform-setups=%d\n", core->frameCounter(core), setups);
    int result = reads >= 4 && setups > 0 ? 0 : 6;
    mCoreConfigDeinit(&core->config);
    core->deinit(core);
    return result;
}
