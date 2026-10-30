package com.github.warren_bank.echo.dsp;

// Processing applied between the microphone and the speaker.
// Samples are floats in [-1, 1]. Flags may change while running.
public final class AudioChain {
  public static final double HIGH_PASS_HZ = 120.0;

  public volatile boolean highPassEnabled = true;
  public volatile boolean limiterEnabled  = true;

  private final Biquad  highPass;
  private final Limiter limiter;

  public AudioChain(int sampleRate) {
    highPass = new Biquad();
    highPass.setHighPass(sampleRate, HIGH_PASS_HZ, 0.7071);
    limiter = new Limiter(sampleRate, 0.89f, 150.0);  // -1 dBFS
  }

  // Returns the input peak (before processing) in [0, 1].
  public float process(short[] pcm, float[] work, int n, float gain) {
    float peak = 0.0f;
    for (int i = 0; i < n; i++) {
      float x = pcm[i] / 32768.0f;
      work[i] = x;
      float a = Math.abs(x);
      if (a > peak) peak = a;
    }

    if (highPassEnabled) highPass.process(work, n);

    for (int i = 0; i < n; i++) work[i] *= gain;

    if (limiterEnabled) limiter.process(work, n);

    for (int i = 0; i < n; i++) {
      float y = work[i] * 32768.0f;
      if (y > 32767.0f) y = 32767.0f;
      else if (y < -32768.0f) y = -32768.0f;
      pcm[i] = (short) y;
    }
    return peak;
  }
}
