package com.github.warren_bank.echo.dsp;

import java.util.Arrays;

// Measures the loop delay (speaker -> room -> mic) and how loud the speaker
// comes back into the mic. Plays a train of short windowed chirps and finds
// each one in the recording by FFT cross-correlation (matched filter).
// The recording must be sample-aligned with the playback the same way the
// echo loop is: block i read from the mic <-> block i written to the speaker.
public final class LatencyAnalyzer {
  public static final int    DEFAULT_PULSES     = 5;
  public static final int    DEFAULT_SPACING_MS = 500;   // allows delays up to ~450 ms (Bluetooth)
  public static final double PULSE_MS           = 20.0;
  public static final double F0_HZ              = 500.0;
  public static final double F1_HZ              = 6000.0;
  public static final int    LEAD_MS            = 300;   // silence while the streams settle

  // Peak over median |correlation|: plain noise stays near 6, so 12 is a safe margin
  public static final double MIN_PEAK_RATIO     = 12.0;
  private static final double AGREE_MS          = 1.0;   // pulses must agree within this

  public static final class Result {
    public final double delayMs;
    public final double delaySamples;
    public final double feedbackDb;    // level coming back, dB relative to what was played
    public final double confidenceDb;  // median peak / background, dB
    public final int    pulsesUsed;    // pulses that agree with the median
    public final boolean reliable;

    Result(double delaySamples, int sampleRate, double feedbackDb, double confidenceDb,
           int pulsesUsed, boolean reliable) {
      this.delaySamples = delaySamples;
      this.delayMs      = delaySamples * 1000.0 / sampleRate;
      this.feedbackDb   = feedbackDb;
      this.confidenceDb = confidenceDb;
      this.pulsesUsed   = pulsesUsed;
      this.reliable     = reliable;
    }

    @Override
    public String toString() {
      return String.format(java.util.Locale.US,
        "delay=%.2f ms, feedback=%.1f dB, confidence=%.1f dB, pulses=%d, reliable=%b",
        delayMs, feedbackDb, confidenceDb, pulsesUsed, reliable);
    }
  }

  private final int sampleRate, pulses, spacing, lead, pulseLen, maxDelay, segLen, fftSize;
  private final float[] pulse;
  private final double pulseEnergy;
  private final double[] pulseRe, pulseIm;  // spectrum of the pulse
  private final Fft fft;

  public LatencyAnalyzer(int sampleRate) {
    this(sampleRate, DEFAULT_PULSES, DEFAULT_SPACING_MS);
  }

  public LatencyAnalyzer(int sampleRate, int pulses, int spacingMs) {
    if (sampleRate < 16000 || pulses < 1 || spacingMs < 100) throw new IllegalArgumentException();
    this.sampleRate = sampleRate;
    this.pulses     = pulses;
    this.spacing    = sampleRate * spacingMs / 1000;
    this.lead       = sampleRate * LEAD_MS / 1000;
    this.pulseLen   = (int) Math.round(sampleRate * PULSE_MS / 1000.0);
    this.maxDelay   = spacing - pulseLen - sampleRate / 100;  // keep 10 ms clear of the next pulse
    this.segLen     = maxDelay + pulseLen;
    int n = 1;
    while (n < segLen) n <<= 1;
    this.fftSize = n;
    this.fft     = new Fft(n);

    // Linear chirp F0 -> F1 with a Hann window
    pulse = new float[pulseLen];
    double t1 = pulseLen / (double) sampleRate, e = 0;
    for (int i = 0; i < pulseLen; i++) {
      double t = i / (double) sampleRate;
      double phase = 2 * Math.PI * (F0_HZ * t + (F1_HZ - F0_HZ) * t * t / (2 * t1));
      double w = 0.5 - 0.5 * Math.cos(2 * Math.PI * i / (pulseLen - 1));
      pulse[i] = (float) (w * Math.sin(phase));
      e += pulse[i] * (double) pulse[i];
    }
    pulseEnergy = e;
    pulseRe = new double[n];
    pulseIm = new double[n];
    for (int i = 0; i < pulseLen; i++) pulseRe[i] = pulse[i];
    fft.transform(pulseRe, pulseIm);
  }

  public int getSampleRate()     { return sampleRate; }
  public int getPulseCount()     { return pulses; }
  public int getMaxDelaySamples(){ return maxDelay; }
  public float[] getPulse()      { return pulse.clone(); }

  // Where pulse k starts in the playback
  public int getPulseOffset(int k) { return lead + k * spacing; }

  // Samples to play (and to record, aligned): lead-in, pulses, and room for the last echo
  public int getTotalSamples() { return getPulseOffset(pulses - 1) + segLen; }

  // The full playback signal, peak = amplitude
  public float[] buildPlayback(float amplitude) {
    float[] out = new float[getTotalSamples()];
    for (int k = 0; k < pulses; k++) {
      int off = getPulseOffset(k);
      for (int i = 0; i < pulseLen; i++) out[off + i] = amplitude * pulse[i];
    }
    return out;
  }

  // recorded: mic samples aligned with buildPlayback(amplitude); n = valid samples
  public Result analyze(float[] recorded, int n, float amplitude) {
    double[] delays = new double[pulses], levels = new double[pulses], ratios = new double[pulses];
    boolean[] good = new boolean[pulses];
    int found = 0;
    double[] re = new double[fftSize], im = new double[fftSize], mag = new double[maxDelay + 1];

    for (int k = 0; k < pulses; k++) {
      int off = getPulseOffset(k);
      if (off + segLen > n) break;
      found++;

      // Cross-correlation: IFFT(X * conj(P)), with IFFT(Y) = conj(FFT(conj(Y))) / N
      Arrays.fill(re, 0); Arrays.fill(im, 0);
      for (int i = 0; i < segLen; i++) re[i] = recorded[off + i];
      fft.transform(re, im);
      for (int i = 0; i < fftSize; i++) {
        double a = re[i], b = im[i], c = pulseRe[i], d = pulseIm[i];
        re[i] = a * c + b * d;
        im[i] = -(b * c - a * d);  // conj of X * conj(P)
      }
      fft.transform(re, im);  // real part / N = correlation (imag ignored)

      int peak = 0;
      double best = -1;
      for (int lag = 0; lag <= maxDelay; lag++) {
        double v = Math.abs(re[lag]) / fftSize;
        mag[lag] = v;
        if (v > best) { best = v; peak = lag; }
      }

      // Sub-sample peak by parabola over |corr|
      double frac = 0;
      if (peak > 0 && peak < maxDelay) {
        double y0 = mag[peak - 1], y1 = mag[peak], y2 = mag[peak + 1];
        double den = y0 - 2 * y1 + y2;
        if (den < 0) frac = 0.5 * (y0 - y2) / den;
      }
      double[] sorted = Arrays.copyOf(mag, maxDelay + 1);
      Arrays.sort(sorted);
      double background = sorted[sorted.length / 2];

      delays[k] = peak + frac;
      levels[k] = 20 * Math.log10(Math.max(best, 1e-12) / (amplitude * pulseEnergy));
      ratios[k] = best / Math.max(background, 1e-12);
      good[k]   = background > 0 && ratios[k] >= MIN_PEAK_RATIO;
    }

    if (found == 0) return new Result(0, sampleRate, -120, 0, 0, false);

    // Median delay over the good pulses (or all, if none is good), then keep those that agree
    double[] cand = pick(delays, good, found);
    boolean anyGood = cand.length > 0;
    if (!anyGood) cand = Arrays.copyOf(delays, found);
    double mid = median(cand);
    double tol = AGREE_MS * sampleRate / 1000.0;
    boolean[] agree = new boolean[pulses];
    int used = 0;
    for (int k = 0; k < found; k++) {
      agree[k] = (good[k] || !anyGood) && Math.abs(delays[k] - mid) <= tol;
      if (agree[k]) used++;
    }
    if (used == 0) { Arrays.fill(agree, 0, found, true); used = found; }

    double delay  = median(pick(delays, agree, found));
    double level  = median(pick(levels, agree, found));
    double ratio  = median(pick(ratios, agree, found));
    boolean reliable = anyGood && used >= found / 2 + 1;
    return new Result(delay, sampleRate, level, 20 * Math.log10(Math.max(ratio, 1e-12)),
                      used, reliable);
  }

  private static double[] pick(double[] v, boolean[] mask, int n) {
    int c = 0;
    for (int i = 0; i < n; i++) if (mask[i]) c++;
    double[] out = new double[c];
    c = 0;
    for (int i = 0; i < n; i++) if (mask[i]) out[c++] = v[i];
    return out;
  }

  static double median(double[] v) {
    if (v.length == 0) return 0;
    double[] s = v.clone();
    Arrays.sort(s);
    int m = s.length / 2;
    return (s.length % 2 == 1) ? s[m] : 0.5 * (s[m - 1] + s[m]);
  }
}
