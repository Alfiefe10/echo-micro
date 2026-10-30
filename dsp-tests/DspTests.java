import com.github.warren_bank.echo.dsp.*;
import java.util.Arrays;
import java.util.Random;

// Plain-Java checks of the audio processing (no Android needed).
// Run: javac -d out <dsp sources> DspTests.java && java -ea -cp out DspTests
public class DspTests {
  static final int SR = 48000;
  static int failures = 0;

  static void check(boolean ok, String what) {
    System.out.println((ok ? "OK    " : "FALLO ") + what);
    if (!ok) failures++;
  }

  static float[] sine(double freq, double amp, int n) {
    float[] buf = new float[n];
    for (int i = 0; i < n; i++) buf[i] = (float) (amp * Math.sin(2 * Math.PI * freq * i / SR));
    return buf;
  }

  static double rmsDb(float[] buf, int from) {
    double s = 0;
    for (int i = from; i < buf.length; i++) s += buf[i] * buf[i];
    return 10 * Math.log10(s / (buf.length - from) * 2);
  }

  static double highPassDb(double freq) {
    Biquad hp = new Biquad();
    hp.setHighPass(SR, 120, 0.7071);
    float[] buf = sine(freq, 1, SR);
    hp.process(buf, buf.length);
    return rmsDb(buf, SR / 2);
  }

  // Speech-like source: harmonics of a moving pitch (or a fixed one), syllables and pauses
  static float[] speech(int n, long seed, boolean steadyPitch) {
    Random r = new Random(seed);
    float[] x = new float[n];
    double ph = 0, env = 0, target = 0;
    for (int i = 0; i < n; i++) {
      if (i % (SR / 5) == 0) target = steadyPitch ? 0.25 : (r.nextDouble() < 0.7 ? 0.25 : 0);
      env += (target - env) * 0.002;
      double f0 = steadyPitch ? 150 : 130 + 25 * Math.sin(2 * Math.PI * 4 * i / SR) + 10 * Math.sin(2 * Math.PI * 0.7 * i / SR);
      ph += 2 * Math.PI * f0 / SR;
      double s = 0;
      for (int k = 1; k <= 25; k++) s += Math.sin(k * ph) / k;
      x[i] = (float) (env * s * 0.5 + 0.002 * r.nextGaussian());
    }
    return x;
  }

  // Room: the speaker's output comes back to the mic 30 ms later, with resonances
  static float[] run(float[] src, Biquad[] room, double loopGain, FeedbackSuppressor fs) {
    int n = src.length, delay = 1440, block = 480;
    float[] out = new float[n], blk = new float[block];
    for (int s = 0; s < n; s += block) {
      for (int i = 0; i < block; i++) {
        int t = s + i;
        float fb = (t >= delay) ? out[t - delay] : 0f;
        if (room != null) {
          for (Biquad b : room) fb = b.process(fb);
          blk[i] = src[t] + (float) (loopGain * fb);
        }
        else blk[i] = src[t];
      }
      if (fs != null) fs.process(blk, block);
      for (int i = 0; i < block; i++) out[s + i] = Math.max(-1f, Math.min(1f, blk[i]));
    }
    return out;
  }

  static Biquad[] room(double... modes) {
    Biquad[] r = new Biquad[modes.length];
    for (int i = 0; i < modes.length; i++) {
      r[i] = new Biquad();
      r[i].setPeaking(SR, modes[i], 4, 9);
    }
    return r;
  }

  static double levelDb(float[] x, int from) {
    double s = 0;
    for (int i = from; i < x.length; i++) s += x[i] * x[i];
    return 10 * Math.log10(s / (x.length - from) + 1e-20);
  }

  static void feedbackTests() {
    int n = SR * 8;
    float[] voice = speech(n, 1, false);

    FeedbackSuppressor fs = new FeedbackSuppressor(SR);
    run(voice, null, 0, fs);
    check(fs.getNotchCount() == 0, "supresor: voz sola, sin cortes (" + fs.getNotchCount() + ")");

    fs = new FeedbackSuppressor(SR);
    run(speech(n, 2, true), null, 0, fs);
    check(fs.getNotchCount() == 0, "supresor: vocal sostenida, sin cortes (" + fs.getNotchCount() + ")");

    for (double[] modes : new double[][]{{1700}, {900, 3100}}) {
      double source = levelDb(voice, n / 2);
      double howl   = levelDb(run(voice, room(modes), 0.4, null), n / 2);
      fs = new FeedbackSuppressor(SR);
      double fixed  = levelDb(run(voice, room(modes), 0.4, fs), n / 2);
      check(howl - source > 10, String.format("sala %s: sin supresor pita (%+.1f dB sobre la voz)", Arrays.toString(modes), howl - source));
      check(fixed - source < 3, String.format("sala %s: con supresor %+.1f dB sobre la voz, cortes %s",
        Arrays.toString(modes), fixed - source, Arrays.toString(fs.getNotchFrequencies())));
    }
  }

  public static void main(String[] args) {
    check(highPassDb(50) < -12, String.format("paso alto: 50 Hz atenuados (%.1f dB)", highPassDb(50)));
    check(Math.abs(highPassDb(1000)) < 0.5, String.format("paso alto: 1 kHz intacto (%.1f dB)", highPassDb(1000)));

    Limiter limiter = new Limiter(SR, 0.89f, 150);
    float[] loud = sine(440, 1.5, 4800);
    limiter.process(loud, loud.length);
    float max = 0;
    for (float v : loud) max = Math.max(max, Math.abs(v));
    check(max <= 0.8901f, String.format("limitador: 1,5 -> pico %.3f", max));

    AudioChain chain = new AudioChain(SR);
    short[] pcm = new short[480];
    float[] work = new float[480];
    for (int i = 0; i < 480; i++) pcm[i] = (short) (20000 * Math.sin(2 * Math.PI * 1000 * i / SR));
    chain.process(pcm, work, 480, 3.0f);
    int peak = 0;
    for (short v : pcm) peak = Math.max(peak, Math.abs(v));
    check(peak <= 29200, "cadena: ganancia x3 sin desbordar (pico " + peak + ")");

    feedbackTests();

    if (failures > 0) {
      System.out.println(failures + " pruebas fallidas");
      System.exit(1);
    }
  }
}
