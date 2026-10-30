package com.github.warren_bank.echo.dsp;

// Processing applied between the microphone and the speaker.
// Samples are floats in [-1, 1]. Flags may change while running.
public final class AudioChain {
  public static final double HIGH_PASS_HZ = 120.0;
  public static final double SHIFT_HZ     = 5.0;

  public volatile boolean highPassEnabled = true;
  public volatile boolean limiterEnabled  = true;
  public volatile boolean feedbackEnabled = true;
  public volatile boolean denoiseEnabled  = false;
  public volatile boolean shiftEnabled    = false;

  private final Biquad  highPass;
  private final Limiter limiter;
  private final FeedbackSuppressor feedback;
  private final RnNoise denoise;
  private final FrequencyShifter shifter;
  private final boolean denoiseUsable;

  public AudioChain(int sampleRate) {
    highPass = new Biquad();
    highPass.setHighPass(sampleRate, HIGH_PASS_HZ, 0.7071);
    limiter = new Limiter(sampleRate, 0.89f, 150.0);  // -1 dBFS
    feedback = new FeedbackSuppressor(sampleRate);
    shifter  = new FrequencyShifter(sampleRate, SHIFT_HZ);
    denoiseUsable = (sampleRate == 48000) && RnNoise.isAvailable();
    denoise = denoiseUsable ? new RnNoise() : null;
  }

  public boolean isDenoiseUsable() {
    return denoiseUsable;
  }

  public void release() {
    if (denoise != null) denoise.release();
  }

  public int getFeedbackCuts() {
    return feedback.getNotchCount();
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

    // RNNoise needs exact 10 ms frames at 48 kHz; otherwise it is skipped
    if (denoiseEnabled && denoiseUsable && n == RnNoise.FRAME_SIZE) denoise.process(work);

    for (int i = 0; i < n; i++) work[i] *= gain;

    if (feedbackEnabled) feedback.process(work, n);

    if (shiftEnabled) shifter.process(work, n);

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
