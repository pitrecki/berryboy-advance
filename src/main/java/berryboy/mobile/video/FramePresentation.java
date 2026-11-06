package berryboy.mobile.video;

import berryboy.mobile.playback.PlaybackSpeed;

/** Presentation-only skipping and measured rates; all guest frames still execute. */
public final class FramePresentation {
    private long started = -1;
    private long guest;
    private int guestTenths;
    private int untilPresentation;

    public boolean next(int skip, long nowMillis) {
        return next(skip, 1, nowMillis);
    }
    public boolean next(int skip, int speed, long nowMillis) {
        var interval = PlaybackSpeed.presentationInterval(skip, speed);
        var show = untilPresentation == 0;
        untilPresentation = show ? interval - 1 : untilPresentation - 1;
        if (started < 0) { started = nowMillis; return show; }
        guest++;
        var elapsed = nowMillis - started;
        if (elapsed >= 1000) {
            guestTenths = (int) (guest * 10000 / elapsed);
            guest = 0; started = nowMillis;
        }
        return show;
    }
    public int guestTenths() { return guestTenths; }
    public void reset() { started = -1; guest = 0; guestTenths = 0; untilPresentation = 0; }
}
