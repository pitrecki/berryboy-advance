package berryboy.timing;

/**
 * Optional synchronous diagnostics on the guest owner. Observers must only copy
 * state: never advance the clock, change devices or dispatch work on live state.
 * A failed callback has a before notification but no successful after notification.
 */
public interface CycleObserver {
    void dispatch(CycleEvent event, boolean before);
}
