package berryboy.video;

import berryboy.timing.CycleScheduler;

/** Read-only device port for video; sampling never charges CPU bus cycles. */
public interface VideoBus {
    int peek8(int address);
    int peek16(int address);
    long videoVersion();
    long displayVersion();
    CycleScheduler clock();
}
