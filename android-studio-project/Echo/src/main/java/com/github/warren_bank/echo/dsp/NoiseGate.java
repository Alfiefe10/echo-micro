package com.github.warren_bank.echo.dsp;

// Soft noise gate (downward expander) driven by an envelope follower.
// Closed, it lowers the signal by FLOOR_DB instead of muting it, so the
// background never cuts in and out. No lookahead: the fast attack keeps
// the start of words.
public final class NoiseGate {
  public static final double DEFAULT_THRESHOLD_DB = -45.0;
  public static final double FLOOR_DB      = -25.0;
  public static final double ATTACK_MS     = 2.0;    // time to open fully
  public static final double HOLD_MS       = 150.0;  // stays open after the level drops
  public static final double RELEASE_MS    = 120.0;  // time to close fully
  private static final double HYSTERESIS_DB = 3.0;   // keeps open down to threshold - 3 dB

  private final float envUp, envDown;     // envelope follower coefficients
  private final double openStep, closeStep;  // dB per sample
  private final int holdSamples;

  private volatile float openLevel, keepLevel;
  private float env = 0f;
  private int hold = 0;
  private double gainDb = FLOOR_DB;
  private float gain = (float) Math.pow(10, FLOOR_DB / 20);

  public NoiseGate(int sampleRate) {
    envUp   = (float) Math.exp(-1.0 / (sampleRate * 0.0005));  // 0.5 ms
    envDown = (float) Math.exp(-1.0 / (sampleRate * 0.010));   // 10 ms
    openStep  = -FLOOR_DB / (sampleRate * ATTACK_MS / 1000.0);
    closeStep = -FLOOR_DB / (sampleRate * RELEASE_MS / 1000.0);
    holdSamples = (int) (sampleRate * HOLD_MS / 1000.0);
    setThresholdDb(DEFAULT_THRESHOLD_DB);
  }

  // Input level (dBFS, rectified envelope) above which the gate opens
  public void setThresholdDb(double db) {
    openLevel = (float) Math.pow(10, db / 20);
    keepLevel = (float) Math.pow(10, (db - HYSTERESIS_DB) / 20);
  }

  public void reset() {
    env = 0f;
    hold = 0;
    gainDb = FLOOR_DB;
    gain = (float) Math.pow(10, FLOOR_DB / 20);
  }

  public void process(float[] buf, int n) {
    float open = openLevel, keep = keepLevel;
    for (int i = 0; i < n; i++) {
      float x = buf[i];
      float a = Math.abs(x);
      env = (a > env) ? a + (env - a) * envUp : a + (env - a) * envDown;

      if (env > open || (hold > 0 && env > keep)) hold = holdSamples;
      else if (hold > 0) hold--;

      double target = (hold > 0) ? 0.0 : FLOOR_DB;
      if (gainDb != target) {
        gainDb = (gainDb < target) ? Math.min(target, gainDb + openStep)
                                   : Math.max(target, gainDb - closeStep);
        gain = (float) Math.pow(10, gainDb / 20);
      }
      buf[i] = x * gain;
    }
  }

  // True while the gate is (or is going) open
  public boolean isOpen() {
    return hold > 0;
  }

  // Current gain applied, 0 dB = fully open
  public double getGainDb() {
    return gainDb;
  }
}
