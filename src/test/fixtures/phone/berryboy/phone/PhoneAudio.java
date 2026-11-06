package berryboy.phone;
/** No physical audio device is supplied by the session test. */
public final class PhoneAudio {
    public static volatile int appends, resets;
    String failure(){return null;}
    void append(short[] pcm,int count){appends++;}
    void reset(){resets++;}
    void close(){}
}
