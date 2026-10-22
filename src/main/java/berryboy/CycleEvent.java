package berryboy;

/** A portable device callback, invoked at its exact guest-cycle deadline. */
public interface CycleEvent {
    void fire();
}
