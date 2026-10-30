package com.github.warren_bank.echo.dsp;

// Automatic gain control: brings the speech RMS level towards TARGET_DB.
// It only adapts while voice is present, so pauses (noise, feedback) are
// never pushed up; the gain is simply held until speech comes back.
public final class AutoGain {
  public static final double TARGET_DB   = -20.0;
  public static final double MIN_GAIN_DB = -6.0;
  public static final double MAX_GAIN_DB = 12.0;
  public static final double DOWN_MS     = 50.0;    // reacts fast to loud speech
  public static final double UP_MS       = 1000.0;  // recovers slowly
  private static final double MIN_LEVEL_DB = -60.0; // ignore blocks quieter than this

  private final int sampleRate;
  private double gainDb = 0.0;
  private float gain = 1.0f;

  public AutoGain(int sampleRate) {
    this.sampleRate = sampleRate;
  }

  public void reset() {
    gainDb = 0.0;
    gain = 1.0f;
  }

  public void process(float[] buf, int n, boolean voicePresent) {
    if (n <= 0) return;
    if (voicePresent) {
      double sum = 0;
      for (int i = 0; i < n; i++) sum += buf[i] * buf[i];
      double levelDb = 10 * Math.log10(sum / n + 1e-20);
      if (levelDb > MIN_LEVEL_DB) {
        double want = Math.max(MIN_GAIN_DB, Math.min(MAX_GAIN_DB, TARGET_DB - levelDb));
        double tau = (want < gainDb) ? DOWN_MS : UP_MS;
        double k = 1 - Math.exp(-n * 1000.0 / (sampleRate * tau));
        gainDb += (want - gainDb) * k;
      }
    }
    // Ramp across the block to avoid zipper noise
    float start = gain, end = (float) Math.pow(10, gainDb / 20);
    float step = (end - start) / n;
    for (int i = 0; i < n; i++) buf[i] *= start + step * (i + 1);
    gain = end;
  }

  public double getGainDb() {
    return gainDb;
  }
}
