package berryboy.mobile;

/** Presentation-only skipping and measured rates; all guest frames still execute. */
public final class FramePresentation {
    private long started = -1;
    private long guest;
    private int guestTenths;
    private int untilPresentation;

    public boolean next(int skip, long nowMillis) {
        if (skip < 0 || skip > 5) throw new IllegalArgumentException("Invalid frame skip");
        var show = untilPresentation == 0;
        untilPresentation = show ? skip : untilPresentation - 1;
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
