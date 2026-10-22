package berryboy.timing;

/** Routes timer edges without coupling the timer unit to sound or DMA. */
public interface TimerOverflowListener {
    void overflow(int timer);
}
