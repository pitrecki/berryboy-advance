package berryboy.mobile.audio;

import berryboy.mobile.settings.PhoneOptions;

/** Post-process owned output PCM; synthesis and guest audio state continue unchanged. */
public final class OutputMixer {
    private OutputMixer() { }
    public static void apply(short[] pcm, int frames, PhoneOptions options) {
        if (frames < 0 || frames > pcm.length / 2) throw new IllegalArgumentException("Invalid PCM length");
        var volume = options.muted() ? 0 : options.volume();
        for (var i = 0; i < frames * 2; i += 2) {
            int left = pcm[i]; int right = pcm[i + 1];
            if (options.mono()) { left = (left + right) / 2; right = left; }
            pcm[i] = (short) (left * volume / 100);
            pcm[i + 1] = (short) (right * volume / 100);
        }
    }
}
