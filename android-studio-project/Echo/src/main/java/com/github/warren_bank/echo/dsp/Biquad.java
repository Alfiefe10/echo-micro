package com.github.warren_bank.echo.dsp;

// Second-order IIR section (RBJ Audio EQ Cookbook), transposed direct form II.
public final class Biquad {
  private double b0, b1, b2, a1, a2;
  private double z1, z2;

  public Biquad() {
    setIdentity();
  }

  public void setIdentity() {
    b0 = 1; b1 = 0; b2 = 0; a1 = 0; a2 = 0;
  }

  public void reset() {
    z1 = 0; z2 = 0;
  }

  public void setHighPass(double sampleRate, double freq, double q) {
    double w0 = 2 * Math.PI * freq / sampleRate;
    double cos = Math.cos(w0), alpha = Math.sin(w0) / (2 * q);
    double a0 = 1 + alpha;
    b0 = ((1 + cos) / 2) / a0;
    b1 = -(1 + cos) / a0;
    b2 = ((1 + cos) / 2) / a0;
    a1 = (-2 * cos) / a0;
    a2 = (1 - alpha) / a0;
  }

  // Peaking EQ: gainDb < 0 cuts a narrow band around freq.
  public void setPeaking(double sampleRate, double freq, double q, double gainDb) {
    double a  = Math.pow(10, gainDb / 40);
    double w0 = 2 * Math.PI * freq / sampleRate;
    double cos = Math.cos(w0), alpha = Math.sin(w0) / (2 * q);
    double a0 = 1 + alpha / a;
    b0 = (1 + alpha * a) / a0;
    b1 = (-2 * cos) / a0;
    b2 = (1 - alpha * a) / a0;
    a1 = (-2 * cos) / a0;
    a2 = (1 - alpha / a) / a0;
  }

  // Low shelf: gainDb applied below freq (q = 0.7071 gives the cookbook slope S = 1).
  public void setLowShelf(double sampleRate, double freq, double q, double gainDb) {
    double a  = Math.pow(10, gainDb / 40);
    double w0 = 2 * Math.PI * freq / sampleRate;
    double cos = Math.cos(w0), alpha = Math.sin(w0) / (2 * q);
    double k = 2 * Math.sqrt(a) * alpha;
    double a0 = (a + 1) + (a - 1) * cos + k;
    b0 = (a * ((a + 1) - (a - 1) * cos + k)) / a0;
    b1 = (2 * a * ((a - 1) - (a + 1) * cos)) / a0;
    b2 = (a * ((a + 1) - (a - 1) * cos - k)) / a0;
    a1 = (-2 * ((a - 1) + (a + 1) * cos)) / a0;
    a2 = ((a + 1) + (a - 1) * cos - k) / a0;
  }

  // High shelf: gainDb applied above freq.
  public void setHighShelf(double sampleRate, double freq, double q, double gainDb) {
    double a  = Math.pow(10, gainDb / 40);
    double w0 = 2 * Math.PI * freq / sampleRate;
    double cos = Math.cos(w0), alpha = Math.sin(w0) / (2 * q);
    double k = 2 * Math.sqrt(a) * alpha;
    double a0 = (a + 1) - (a - 1) * cos + k;
    b0 = (a * ((a + 1) + (a - 1) * cos + k)) / a0;
    b1 = (-2 * a * ((a - 1) + (a + 1) * cos)) / a0;
    b2 = (a * ((a + 1) + (a - 1) * cos - k)) / a0;
    a1 = (2 * ((a - 1) - (a + 1) * cos)) / a0;
    a2 = ((a + 1) - (a - 1) * cos - k) / a0;
  }

  // Copies coefficients and state, so a twin can continue the same signal.
  public void copyFrom(Biquad o) {
    b0 = o.b0; b1 = o.b1; b2 = o.b2; a1 = o.a1; a2 = o.a2;
    z1 = o.z1; z2 = o.z2;
  }

  public float process(float x) {
    double y = b0 * x + z1;
    z1 = b1 * x - a1 * y + z2;
    z2 = b2 * x - a2 * y;
    return (float) y;
  }

  public void process(float[] buf, int n) {
    for (int i = 0; i < n; i++) buf[i] = process(buf[i]);
  }
}
