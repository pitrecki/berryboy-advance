/* mGBA 0.10.5 headless reference stream. Local ROM/BIOS assets are required.
 * stdout is BBREF001 followed by little-endian frame headers and RGB/PCM data.
 */
#include <mgba/core/core.h>
#include <mgba/core/config.h>
#include <mgba/core/log.h>
#include <mgba/core/timing.h>
#include <mgba/core/blip_buf.h>
#include <mgba/internal/arm/arm.h>
#include <mgba/internal/gba/gba.h>
#include <mgba-util/vfs.h>
#include <fcntl.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#define FRAME_CYCLES 280896U
#define PIXELS (240 * 160)
#define PCM_CAPACITY 8192

struct Input { unsigned long long cycle; unsigned keys; };
struct Capture {
    struct mCore* core;
    color_t* pixels;
    struct Input* inputs;
    size_t inputCount, inputIndex;
    struct mTimingEvent inputEvent, frameEvent;
    unsigned frame, frames;
    void* save;
    size_t saveSize;
};
static void quiet(struct mLogger* l, int c, enum mLogLevel v, const char* f, va_list a) {
    (void)l; (void)c; (void)v; (void)f; (void)a;
}
static void put32(uint32_t value) {
    unsigned char bytes[4] = {value, value >> 8, value >> 16, value >> 24};
    if (fwrite(bytes, 1, 4, stdout) != 4) exit(10);
}

static void inputEvent(struct mTiming* timing, void* context, uint32_t late) {
    (void)late;
    struct Capture* c = context;
    uint32_t now = mTimingCurrentTime(timing);
    while (c->inputIndex < c->inputCount && c->inputs[c->inputIndex].cycle <= now)
        c->core->setKeys(c->core, c->inputs[c->inputIndex++].keys);
    if (c->inputIndex < c->inputCount && c->inputs[c->inputIndex].cycle <= c->frames * FRAME_CYCLES)
        mTimingScheduleAbsolute(timing, &c->inputEvent, c->inputs[c->inputIndex].cycle);
}

static void frameEvent(struct mTiming* timing, void* context, uint32_t late) {
    (void)late;
    struct Capture* c = context;
    struct mCore* core = c->core;
    struct ARMCore* cpu = core->cpu;
    blip_t* left = core->getAudioChannel(core, 0);
    blip_t* right = core->getAudioChannel(core, 1);
    int samples = blip_samples_avail(left);
    if (samples != blip_samples_avail(right) || samples > PCM_CAPACITY) exit(11);
    unsigned char rgb[PIXELS * 3];
    short pcm[PCM_CAPACITY * 2];
    unsigned char pcmBytes[PCM_CAPACITY * 4];
    if (blip_read_samples(left, pcm, samples, 1) != samples
            || blip_read_samples(right, pcm + 1, samples, 1) != samples) exit(11);
    for (int i = 0; i < PIXELS; i++) {
        rgb[i * 3] = c->pixels[i];
        rgb[i * 3 + 1] = c->pixels[i] >> 8;
        rgb[i * 3 + 2] = c->pixels[i] >> 16;
    }
    for (int i = 0; i < samples * 2; i++) {
        pcmBytes[i * 2] = pcm[i];
        pcmBytes[i * 2 + 1] = (unsigned short)pcm[i] >> 8;
    }
    put32(++c->frame);
    put32(mTimingCurrentTime(timing)); put32(0);
    put32(cpu->gprs[15] - (cpu->executionMode == MODE_THUMB ? 2 : 4));
    put32(cpu->cpsr.packed); put32(samples);
    if (fwrite(rgb, 1, sizeof(rgb), stdout) != sizeof(rgb)
            || fwrite(pcmBytes, 1, samples * 4, stdout) != (size_t)samples * 4) exit(10);
    if (c->frame < c->frames) mTimingScheduleAbsolute(timing, &c->frameEvent, (c->frame + 1) * FRAME_CYCLES);
    else {
        c->saveSize = core->savedataClone(core, &c->save);
        ((struct GBA*)core->board)->earlyExit = true;
    }
}

int main(int argc, char** argv) {
    if (argc != 7 && argc != 8) {
        fprintf(stderr, "Usage: mgba-capture <rom> <bios> <input.tsv> <frames> <save-output> <devices:0|1> [initial-save]\n");
        return 2;
    }
    char* end;
    long frames = strtol(argv[4], &end, 10);
    if (*end || frames < 1 || frames > 7000) return 2; /* Bound 32-bit guest time. */
    long devices = strtol(argv[6], &end, 10);
    if (*end || devices < 0 || devices > 1) return 2;
    FILE* input = fopen(argv[3], "r");
    if (!input) return 3;
    struct Input* events = NULL;
    size_t count = 0, capacity = 0;
    char line[256];
    while (fgets(line, sizeof(line), input)) {
        char* first = line + strspn(line, " \t\r\n");
        if (!*first || *first == '#') continue;
        unsigned long long cycle;
        unsigned keys, x, y, gyro, solar;
        char extra;
        if (sscanf(first, "%llu %u %u %u %u %u %c", &cycle, &keys, &x, &y, &gyro, &solar, &extra) != 6
                || cycle > INT32_MAX || keys > 1023 || x != 2048 || y != 2048 || gyro != 0 || solar != 0
                || (count && cycle <= events[count - 1].cycle)) return 4;
        if (count == capacity) {
            capacity = capacity ? capacity * 2 : 32;
            void* replacement = realloc(events, capacity * sizeof(*events));
            if (!replacement) return 4;
            events = replacement;
        }
        events[count++] = (struct Input){cycle, keys};
    }
    if (ferror(input)) return 4;
    fclose(input);
    struct mLogger logger = {.log = quiet};
    mLogSetDefaultLogger(&logger);
    struct mCore* core = mCoreFind(argv[1]);
    if (!core || !core->init(core)) return 5;
    mCoreConfigInit(&core->config, NULL);
    mCoreConfigSetDefaultIntValue(&core->config, "useBios", 1);
    mCoreConfigSetDefaultIntValue(&core->config, "skipBios", 0);
    mCoreConfigSetDefaultIntValue(&core->config, "gba.audioHle", 0);
    mCoreLoadForeignConfig(core, &core->config); /* Do not load user configuration. */
    core->rtc.override = RTC_FAKE_EPOCH;
    core->rtc.value = 946684800000LL; /* Milliseconds: 2000-01-01T00:00:00Z. */
    color_t* pixels = calloc(PIXELS, sizeof(*pixels));
    if (!pixels || sizeof(color_t) != 4) return 5;
    core->setVideoBuffer(core, pixels, 240);
    core->setAudioBufferSize(core, PCM_CAPACITY);
    if (!mCoreLoadFile(core, argv[1])) return 6;
    struct VFile* bios = VFileOpen(argv[2], O_RDONLY);
    if (!bios || !core->loadBIOS(core, bios, 0)) return 7;
    if (argc == 8 && !mCoreLoadSaveFile(core, argv[7], true)) return 8;
    core->reset(core);
    struct GBACartridgeHardware* hardware = &((struct GBA*)core->board)->memory.hw;
    GBAHardwareClear(hardware);
    hardware->devices = HW_NONE;
    if (devices == 1) GBAHardwareInitRTC(hardware);
    blip_t* left = core->getAudioChannel(core, 0);
    blip_t* right = core->getAudioChannel(core, 1);
    blip_set_rates(left, core->frequency(core), 32768);
    blip_set_rates(right, core->frequency(core), 32768);
    struct Capture capture = {.core = core, .pixels = pixels, .inputs = events, .inputCount = count, .frames = frames};
    capture.inputEvent = (struct mTimingEvent){.context = &capture, .callback = inputEvent, .name = "recorded input", .priority = 0};
    capture.frameEvent = (struct mTimingEvent){.context = &capture, .callback = frameEvent, .name = "reference frame", .priority = 0xffff};
    if (fwrite("BBREF001", 1, 8, stdout) != 8) return 10;
    mTimingScheduleAbsolute(core->timing, &capture.frameEvent, FRAME_CYCLES);
    if (count && events[0].cycle <= (unsigned long long)frames * FRAME_CYCLES)
        mTimingScheduleAbsolute(core->timing, &capture.inputEvent, events[0].cycle);
    while (capture.frame < capture.frames) core->runLoop(core);
    if (capture.saveSize && capture.save) {
        FILE* output = fopen(argv[5], "wb");
        if (!output || fwrite(capture.save, 1, capture.saveSize, output) != capture.saveSize || fclose(output)) return 12;
    }
    free(capture.save); free(pixels); free(events);
    mCoreConfigDeinit(&core->config); core->deinit(core);
    if (fflush(stdout)) return 10;
    return 0;
}
