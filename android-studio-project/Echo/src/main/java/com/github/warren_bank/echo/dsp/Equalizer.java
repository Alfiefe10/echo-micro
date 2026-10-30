package com.github.warren_bank.echo.dsp;

// Fixed tone presets built from a few biquads.
// setPreset may be called from any thread; the change is applied on the
// audio thread with a short crossfade from the old filters to the new ones.
public final class Equalizer {
  public static final int FLAT          = 0;
  public static final int CLEAR_VOICE   = 1;  // presence +4 dB at 3 kHz, less boom at 250 Hz
  public static final int SMALL_SPEAKER = 2;  // no lows under 180 Hz, presence, a bit of air

  private static final int STAGES = 3;
  private static final double FADE_MS = 20.0;

  private final int sampleRate;
  private final int fadeSamples;
  private Biquad[] active = new Biquad[STAGES];
  private Biquad[] fading = new Biquad[STAGES];
  private volatile int requested = FLAT;
  private int current = FLAT;
  private int fadePos = 0;  // samples left in the crossfade

  public Equalizer(int sampleRate) {
    this.sampleRate = sampleRate;
    this.fadeSamples = Math.max(1, (int) (sampleRate * FADE_MS / 1000.0));
    for (int i = 0; i < STAGES; i++) {
      active[i] = new Biquad();
      fading[i] = new Biquad();
    }
  }

  public void setPreset(int preset) {
    if (preset < FLAT || preset > SMALL_SPEAKER) preset = FLAT;
    requested = preset;
  }

  public int getPreset() {
    return requested;
  }

  public void reset() {
    for (int i = 0; i < STAGES; i++) {
      active[i].reset();
      fading[i].reset();
    }
    fadePos = 0;
  }

  private void configure(Biquad[] f, int preset) {
    for (Biquad b : f) b.setIdentity();
    double sr = sampleRate;
    switch (preset) {
      case CLEAR_VOICE:
        f[0].setPeaking(sr, 3000, 1.0, 4.0);
        f[1].setPeaking(sr, 250, 1.0, -2.5);
        break;
      case SMALL_SPEAKER:
        f[0].setHighPass(sr, 180, 0.7071);
        f[1].setPeaking(sr, 2500, 1.0, 3.0);
        f[2].setHighShelf(sr, 6000, 0.7071, 2.0);
        break;
      default:
        break;
    }
  }

  public void process(float[] buf, int n) {
    int want = requested;
    if (want != current) {
      // Old filters keep running in 'fading'; the new ones start from their state
      Biquad[] t = fading; fading = active; active = t;
      for (int i = 0; i < STAGES; i++) active[i].copyFrom(fading[i]);
      configure(active, want);
      current = want;
      fadePos = fadeSamples;
    }
    for (int i = 0; i < n; i++) {
      float x = buf[i];
      float y = x;
      for (int s = 0; s < STAGES; s++) y = active[s].process(y);
      if (fadePos > 0) {
        float old = x;
        for (int s = 0; s < STAGES; s++) old = fading[s].process(old);
        float t = (float) fadePos / fadeSamples;  // 1 -> 0
        y = y + (old - y) * t;
        fadePos--;
      }
      buf[i] = y;
    }
  }
}
