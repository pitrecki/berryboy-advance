package berryboy;

/** A diagnostic stop; does not depend on newer Java SE exception classes. */
public final class EmulationException extends RuntimeException {
    public EmulationException(String message) { super(message); }
}
