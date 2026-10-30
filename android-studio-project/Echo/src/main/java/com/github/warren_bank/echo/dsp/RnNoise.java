package com.github.warren_bank.echo.dsp;

// Neural noise suppression (RNNoise, Xiph). Needs the native library;
// isAvailable() is false when it could not be loaded (e.g. plain-Java tests).
public final class RnNoise {
  public static final int FRAME_SIZE = 480;  // 10 ms at 48 kHz

  private static final boolean LOADED;
  static {
    boolean ok;
    try {
      System.loadLibrary("echornnoise");
      ok = true;
    }
    catch (Throwable t) {
      ok = false;
    }
    LOADED = ok;
  }

  public static boolean isAvailable() {
    return LOADED;
  }

  private long state;
  private final float[] frame = new float[FRAME_SIZE];

  public RnNoise() {
    state = LOADED ? nativeCreate() : 0;
  }

  // Processes exactly FRAME_SIZE samples in [-1, 1], in place. Returns the voice probability.
  public float process(float[] buf) {
    if (state == 0) return 1.0f;
    for (int i = 0; i < FRAME_SIZE; i++) frame[i] = buf[i] * 32768.0f;
    float vad = nativeProcess(state, frame);
    for (int i = 0; i < FRAME_SIZE; i++) buf[i] = frame[i] / 32768.0f;
    return vad;
  }

  public void release() {
    if (state != 0) {
      nativeDestroy(state);
      state = 0;
    }
  }

  private static native long  nativeCreate();
  private static native void  nativeDestroy(long state);
  private static native int   nativeFrameSize();
  private static native float nativeProcess(long state, float[] frame);
}
