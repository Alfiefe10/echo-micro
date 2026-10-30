package com.github.warren_bank.echo.dsp;

// Peak limiter without lookahead: instant attack, smooth release.
// Keeps a sudden howl or a shout from clipping the speaker.
public final class Limiter {
  private final float threshold;
  private final float release;
  private float gain = 1.0f;

  public Limiter(double sampleRate, float threshold, double releaseMs) {
    this.threshold = threshold;
    this.release   = (float) Math.exp(-1.0 / (sampleRate * releaseMs / 1000.0));
  }

  public void reset() {
    gain = 1.0f;
  }

  public void process(float[] buf, int n) {
    for (int i = 0; i < n; i++) {
      float x = buf[i];
      float level = Math.abs(x);
      float target = (level > threshold) ? threshold / level : 1.0f;
      if (target < gain) gain = target;
      else gain = target + (gain - target) * release;
      buf[i] = x * gain;
    }
  }

  // Current gain reduction, 1 = none
  public float getGain() {
    return gain;
  }
}
