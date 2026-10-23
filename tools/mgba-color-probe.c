/* Original settled backdrop brightness cases, matching nba-color-probe.cc.
 * Compile with COLOR_16_BIT against unmodified mGBA 0.10.5 RGB555.
 * Uses a generated cartridge, no commercial ROM/BIOS. CPU runs B . in IWRAM.
 */
#include <mgba/core/core.h>
#include <mgba/core/config.h>
#include <mgba/core/log.h>
#include <mgba/core/version.h>
#include <mgba-util/vfs.h>
#include <stdint.h>
#include <stdio.h>
#include <string.h>

#if !defined(COLOR_16_BIT) || defined(COLOR_5_6_5)
#error "This probe requires the RGB555 library and COLOR_16_BIT adapter ABI"
#endif

static void quiet(struct mLogger* logger, int category, enum mLogLevel level,
        const char* format, va_list args) {
    (void)logger; (void)category; (void)level; (void)format; (void)args;
}

static unsigned rgb888(color_t color) {
    unsigned rgb = 0;
    for (unsigned channel = 0; channel < 3; ++channel) {
        unsigned value = (color >> (channel * 5)) & 31;
        rgb = (rgb << 8) | (value << 3) | (value >> 2);
    }
    return rgb;
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
    struct VFile* rom = VFileMemChunk(image, sizeof(image));
    if (!rom || !core->loadROM(core, rom)) return 4;
    color_t pixels[240 * 160];
    core->setVideoBuffer(core, pixels, 240);
    puts("color,effect,coefficient,high_bit,rgb");
    for (unsigned index = 0; index < 33; ++index) {
        unsigned color = index == 32 ? 0x7e51 : index * 0x421;
        for (unsigned effect = 2; effect <= 3; ++effect) {
            for (unsigned coefficient = 0; coefficient <= 16; ++coefficient) {
                for (unsigned high_bit = 0; high_bit < 2; ++high_bit) {
                    core->reset(core);
                    core->rawWrite32(core, 0x03000000, -1, 0xeafffffe);
                    uint32_t status = 0x1f, pc = 0x03000000;
                    if (!core->writeRegister(core, "cpsr", &status) || !core->writeRegister(core, "pc", &pc)) return 5;
                    core->busWrite16(core, 0x05000000, color | (high_bit << 15));
                    core->busWrite16(core, 0x04000000, 0);
                    core->busWrite16(core, 0x04000050, 0x20 | (effect << 6));
                    core->busWrite16(core, 0x04000054, coefficient);
                    core->runFrame(core); core->runFrame(core);
                    unsigned rgb = rgb888(pixels[0]);
                    for (unsigned i = 1; i < sizeof(pixels) / sizeof(pixels[0]); ++i) {
                        if (rgb888(pixels[i]) != rgb) return 6;
                    }
                    printf("0x%04x,%u,%u,%u,0x%06x\n", color, effect, coefficient, high_bit, rgb);
                }
            }
        }
    }
    mCoreConfigDeinit(&core->config);
    core->deinit(core);
    return fflush(stdout) ? 7 : 0;
}
