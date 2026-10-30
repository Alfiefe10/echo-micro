package com.github.warren_bank.echo.dsp;

// Automatic feedback (howl) suppressor.
//
// A howl is a narrow peak that stays at the same frequency, stands far above its
// neighbours and has no harmonics; voiced speech has harmonics and its pitch moves.
// When a peak passes those tests for long enough, a narrow cut (peaking EQ, Q 30)
// is placed on it; if it comes back, the cut gets deeper.
public final class FeedbackSuppressor {
  private static final int    FFT_SIZE      = 2048;   // ~43 ms at 48 kHz, 23 Hz per bin
  private static final int    MAX_NOTCHES   = 10;
  private static final double MIN_FREQ      = 150.0;
  private static final double MAX_FREQ      = 10000.0;
  private static final double MIN_LEVEL_DB  = -50.0;  // dBFS of the peak
  private static final double NEIGHBOUR_DB  = 18.0;   // peak above bins 4..8 away
  private static final double HARMONIC_DB   = 20.0;   // peak above its 2nd and 3rd harmonics
  private static final double PERSIST_MS    = 150.0;  // how long a candidate must last
  private static final double LOUD_DB       = -15.0;  // a peak this loud is treated as an emergency:
  private static final double LOUD_PERSIST_MS = 60.0; //   faster, and clipping harmonics are ignored
  private static final double FIRST_CUT_DB  = -12.0;
  private static final double STEP_CUT_DB   = -6.0;
  private static final double MAX_CUT_DB    = -30.0;
  private static final double NOTCH_Q       = 30.0;
  private static final double RAMP_DB       = 1.5;    // per processed block
  private static final double SAME_FREQ     = 0.02;   // 2 %

  private static final class Notch {
    final Biquad filter = new Biquad();
    double freq;
    double gainDb;      // current
    double targetDb;    // where it is heading
    long   lastHit;
  }

  private final int sampleRate;
  private final Fft fft = new Fft(FFT_SIZE);
  private final double[] window = new double[FFT_SIZE];
  private final double[] re = new double[FFT_SIZE];
  private final double[] im = new double[FFT_SIZE];
  private final double[] magDb = new double[FFT_SIZE / 2];
  private final float[] history = new float[FFT_SIZE];
  private final int analysisHop;
  private final int persistHops;
  private final int loudPersistHops;
  private boolean candidateLoud = false;
  // Counts howl events (new or repeated cuts) for the caller's gain back-off
  private int howlEvents = 0;
  private int historyPos = 0;
  private int sinceAnalysis = 0;
  private long hops = 0;

  private final Notch[] notches = new Notch[MAX_NOTCHES];
  private int notchCount = 0;

  private double candidateFreq = 0;
  private int candidateHops = 0;

  public FeedbackSuppressor(int sampleRate) {
    this.sampleRate = sampleRate;
    for (int i = 0; i < FFT_SIZE; i++)
      window[i] = 0.5 - 0.5 * Math.cos(2 * Math.PI * i / (FFT_SIZE - 1));
    analysisHop = sampleRate / 50;  // every 20 ms
    persistHops = (int) Math.ceil(PERSIST_MS / 20.0);
    loudPersistHops = (int) Math.ceil(LOUD_PERSIST_MS / 20.0);
  }

  public synchronized void reset() {
    notchCount = 0;
    candidateHops = 0;
  }

  public synchronized int getNotchCount() {
    return notchCount;
  }

  public synchronized int getHowlEvents() {
    return howlEvents;
  }

  public synchronized double[] getNotchFrequencies() {
    double[] out = new double[notchCount];
    for (int i = 0; i < notchCount; i++) out[i] = notches[i].freq;
    return out;
  }

  public synchronized void process(float[] buf, int n) {
    // Ramp the cuts, then filter
    for (int k = 0; k < notchCount; k++) {
      Notch notch = notches[k];
      if (notch.gainDb != notch.targetDb) {
        notch.gainDb = Math.max(notch.targetDb, notch.gainDb - RAMP_DB);
        notch.filter.setPeaking(sampleRate, notch.freq, NOTCH_Q, notch.gainDb);
      }
      notch.filter.process(buf, n);
    }

    // Analyse what is about to leave, after the cuts: a howl that survives gets a deeper cut
    for (int i = 0; i < n; i++) {
      history[historyPos] = buf[i];
      historyPos = (historyPos + 1) % FFT_SIZE;
    }
    sinceAnalysis += n;
    if (sinceAnalysis >= analysisHop) {
      sinceAnalysis = 0;
      hops++;
      analyse();
    }
  }

  private double binDb(int bin) {
    if (bin < 1 || bin >= magDb.length) return -200;
    return magDb[bin];
  }

  private void analyse() {
    for (int i = 0; i < FFT_SIZE; i++) {
      re[i] = history[(historyPos + i) % FFT_SIZE] * window[i];
      im[i] = 0;
    }
    fft.transform(re, im);
    // Hann window gain is 0.5: scale so a full-scale sine reads ~0 dBFS
    double scale = 4.0 / FFT_SIZE;
    for (int k = 0; k < magDb.length; k++) {
      double mag = Math.hypot(re[k], im[k]) * scale;
      magDb[k] = 20 * Math.log10(mag + 1e-12);
    }

    double binHz = (double) sampleRate / FFT_SIZE;
    int lo = (int) (MIN_FREQ / binHz), hi = Math.min(magDb.length - 9, (int) (MAX_FREQ / binHz));

    // Strongest local maximum in the band
    int best = -1;
    for (int k = lo; k <= hi; k++) {
      if (magDb[k] > magDb[k - 1] && magDb[k] >= magDb[k + 1] && (best < 0 || magDb[k] > magDb[best])) best = k;
    }

    double freq = 0;
    boolean loud = best > 0 && magDb[best] > LOUD_DB;
    if (best > 0 && magDb[best] > MIN_LEVEL_DB && isHowlShaped(best, loud)) {
      // Parabolic interpolation for a sub-bin frequency
      double a = magDb[best - 1], b = magDb[best], c = magDb[best + 1];
      double offset = 0.5 * (a - c) / (a - 2 * b + c);
      freq = (best + offset) * binHz;
    }

    if (freq > 0 && candidateHops > 0 && Math.abs(freq - candidateFreq) <= Math.max(binHz, candidateFreq * SAME_FREQ)) {
      candidateHops++;
      candidateLoud = loud;
    }
    else {
      candidateFreq = freq;
      candidateHops = (freq > 0) ? 1 : 0;
      candidateLoud = loud;
    }

    if (candidateHops >= (candidateLoud ? loudPersistHops : persistHops)) {
      cut(candidateFreq);
      candidateHops = 0;
    }
  }

  private boolean isHowlShaped(int k, boolean loud) {
    double peak = magDb[k];

    double neighbours = -200;
    for (int d = 4; d <= 8; d++) {
      neighbours = Math.max(neighbours, binDb(k - d));
      neighbours = Math.max(neighbours, binDb(k + d));
    }
    if (peak - neighbours < NEIGHBOUR_DB) return false;

    // A howl that already saturates grows harmonics of its own
    if (loud) return true;

    double harmonics = Math.max(maxAround(2 * k), maxAround(3 * k));
    return peak - harmonics >= HARMONIC_DB;
  }

  private double maxAround(int k) {
    return Math.max(binDb(k - 1), Math.max(binDb(k), binDb(k + 1)));
  }

  private void cut(double freq) {
    howlEvents++;
    for (int i = 0; i < notchCount; i++) {
      Notch notch = notches[i];
      if (Math.abs(notch.freq - freq) <= notch.freq * SAME_FREQ) {
        notch.targetDb = Math.max(MAX_CUT_DB, notch.targetDb + STEP_CUT_DB);
        notch.lastHit = hops;
        return;
      }
    }

    Notch notch;
    if (notchCount < MAX_NOTCHES) {
      notch = new Notch();
      notches[notchCount++] = notch;
    }
    else {
      // Reuse the one that has been quiet the longest
      notch = notches[0];
      for (int i = 1; i < notchCount; i++)
        if (notches[i].lastHit < notch.lastHit) notch = notches[i];
      notch.filter.reset();
    }
    notch.freq     = freq;
    notch.gainDb   = 0;
    notch.targetDb = FIRST_CUT_DB;
    notch.lastHit  = hops;
  }
}
