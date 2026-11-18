package berryboy.phone;
/** No physical audio device is supplied by the session test. */
public final class PhoneAudio {
    public static volatile int appends, resets;
    /** Optional bounded capture adapter; the session never shares its reusable PCM array. */
    public static volatile java.util.function.Consumer<short[]> packetHook;
    String failure(){return null;}
    void append(short[] pcm,int count){appends++;var hook=packetHook;if(hook!=null)hook.accept(java.util.Arrays.copyOf(pcm,count*2));}
    void reset(){resets++;}
    void close(){}
}
