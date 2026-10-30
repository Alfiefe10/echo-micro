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
  // Strong howl protection: also turn the whole level down while howls keep coming
  public volatile boolean backoffEnabled  = false;
  // 0 off, otherwise the gate threshold in dBFS
  public volatile double  gateThresholdDb = 0.0;
  public volatile boolean autoGainEnabled = false;
  public volatile int     eqPreset        = Equalizer.FLAT;

  private static final double BACKOFF_STEP_DB    = -3.0;
  private static final double BACKOFF_MAX_DB     = -12.0;
  private static final double BACKOFF_HOLD_S     = 4.0;   // calm time before recovering
  private static final double BACKOFF_RECOVER_DB = 1.0;   // per second

  private final Biquad  highPass;
  private final Limiter limiter;
  private final FeedbackSuppressor feedback;
  private final RnNoise denoise;
  private final FrequencyShifter shifter;
  private final NoiseGate noiseGate;
  private final AutoGain autoGain;
  private final Equalizer equalizer;
  private final int sampleRate;
  private int lastHowlEvents = 0;
  private double backoffDb = 0.0;
  private double calmSeconds = 0.0;
  private final boolean denoiseUsable;

  public AudioChain(int sampleRate) {
    this.sampleRate = sampleRate;
    highPass = new Biquad();
    highPass.setHighPass(sampleRate, HIGH_PASS_HZ, 0.7071);
    limiter = new Limiter(sampleRate, 0.89f, 150.0);  // -1 dBFS
    feedback = new FeedbackSuppressor(sampleRate);
    shifter  = new FrequencyShifter(sampleRate, SHIFT_HZ);
    noiseGate = new NoiseGate(sampleRate);
    autoGain  = new AutoGain(sampleRate);
    equalizer = new Equalizer(sampleRate);
    denoiseUsable = (sampleRate == 48000) && RnNoise.isAvailable();
    denoise = denoiseUsable ? new RnNoise() : null;
  }

  // Without the gate: voice if the block is above the default gate threshold
  private static boolean detectVoice(float[] buf, int n) {
    double sum = 0;
    for (int i = 0; i < n; i++) sum += buf[i] * buf[i];
    double db = 10 * Math.log10(sum / n + 1e-12);
    return db > NoiseGate.DEFAULT_THRESHOLD_DB;
  }

  public double getAutoGainDb() {
    return autoGain.getGainDb();
  }

  private void updateBackoff(int n) {
    int events = feedback.getHowlEvents();
    boolean howl = events != lastHowlEvents;
    lastHowlEvents = events;

    if (!backoffEnabled || !feedbackEnabled) {
      backoffDb = 0.0;
      return;
    }
    if (howl) {
      backoffDb = Math.max(BACKOFF_MAX_DB, backoffDb + BACKOFF_STEP_DB);
      calmSeconds = 0.0;
    }
    else {
      double seconds = (double) n / sampleRate;
      calmSeconds += seconds;
      if (calmSeconds > BACKOFF_HOLD_S)
        backoffDb = Math.min(0.0, backoffDb + BACKOFF_RECOVER_DB * seconds);
    }
  }

  // Current automatic level reduction in dB (0 = none)
  public double getBackoffDb() {
    return backoffDb;
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

    // The gate also tells the automatic volume when someone is speaking
    double threshold = gateThresholdDb;
    boolean gateOn = threshold < 0.0;
    if (gateOn) {
      noiseGate.setThresholdDb(threshold);
      noiseGate.process(work, n);
    }
    if (autoGainEnabled) {
      if (!gateOn) noiseGate.setThresholdDb(NoiseGate.DEFAULT_THRESHOLD_DB);
      boolean voice = gateOn ? noiseGate.isOpen() : detectVoice(work, n);
      autoGain.process(work, n, voice);
    }

    if (equalizer.getPreset() != eqPreset) equalizer.setPreset(eqPreset);
    equalizer.process(work, n);

    float backoff = (float) Math.pow(10, backoffDb / 20);
    for (int i = 0; i < n; i++) work[i] *= gain * backoff;

    if (feedbackEnabled) feedback.process(work, n);
    updateBackoff(n);

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
