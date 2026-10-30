package com.github.warren_bank.echo.dsp;

// Shifts every frequency up by a few Hz (single-sideband modulation).
// The sound that loops back from the speaker never lines up with itself again,
// which gives some extra margin before feedback. Fine for speech, not for music.
//
// The analytic signal comes from two chains of second-order allpass filters whose
// outputs stay ~90 degrees apart across the audio band (O. Niemitalo's IIR Hilbert
// design), so there is practically no added delay.
public final class FrequencyShifter {
  private static final double[] COEF_A = {0.6923878, 0.9360654322959, 0.9882295226860, 0.9987488452737};
  private static final double[] COEF_B = {0.4021921162426, 0.8561710882420, 0.9722909545651, 0.9952884791278};

  private static final class Allpass {
    private final double c;
    private double x1, x2, y1, y2;

    Allpass(double a) {
      c = a * a;
    }

    double process(double x) {
      double y = c * (x + y2) - x2;
      x2 = x1; x1 = x;
      y2 = y1; y1 = y;
      return y;
    }
  }

  private final Allpass[] chainA = new Allpass[COEF_A.length];
  private final Allpass[] chainB = new Allpass[COEF_B.length];
  private final double step;
  private double phase = 0;
  private double delayedA = 0;

  public FrequencyShifter(int sampleRate, double shiftHz) {
    for (int i = 0; i < COEF_A.length; i++) chainA[i] = new Allpass(COEF_A[i]);
    for (int i = 0; i < COEF_B.length; i++) chainB[i] = new Allpass(COEF_B[i]);
    step = 2 * Math.PI * shiftHz / sampleRate;
  }

  public void process(float[] buf, int n) {
    for (int i = 0; i < n; i++) {
      double x = buf[i];
      double a = x, b = x;
      for (Allpass f : chainA) a = f.process(a);
      for (Allpass f : chainB) b = f.process(b);

      // Chain A is used one sample late
      double re = delayedA;
      delayedA = a;
      double im = b;

      buf[i] = (float) (re * Math.cos(phase) + im * Math.sin(phase));
      phase += step;
      if (phase > 2 * Math.PI) phase -= 2 * Math.PI;
    }
  }
}
