package berryboy.phone;
/** No physical audio device is supplied by the session test. */
final class PhoneAudio {
    String failure(){return null;}
    void append(short[] pcm,int count){}
    void reset(){}
    void close(){}
}
