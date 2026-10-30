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

  static void denoiseTests() {
    if (!RnNoise.isAvailable()) {
      System.out.println("--    RNNoise: librería nativa no cargada, pruebas omitidas");
      return;
    }
    int n = SR * 6;
    float[] voice = speech(n, 3, false);
    Random r = new Random(4);
    float[] noisy = new float[n];
    for (int i = 0; i < n; i++) noisy[i] = voice[i] + (float) (0.02 * r.nextGaussian());

    AudioChain chain = new AudioChain(SR);
    chain.highPassEnabled = false;
    chain.limiterEnabled  = false;
    chain.feedbackEnabled = false;
    chain.denoiseEnabled  = true;
    check(chain.isDenoiseUsable(), "RNNoise: disponible a 48 kHz");

    short[] pcm = new short[480];
    float[] work = new float[480];
    float[] out = new float[n];
    for (int s = 0; s + 480 <= n; s += 480) {
      for (int i = 0; i < 480; i++) pcm[i] = (short) Math.max(-32768, Math.min(32767, noisy[s + i] * 32768));
      chain.process(pcm, work, 480, 1.0f);
      for (int i = 0; i < 480; i++) out[s + i] = pcm[i] / 32768f;
    }
    chain.release();

    // Noise in the pauses (where the source is silent) and level while speaking
    // A sample is in a pause when the voice is quiet for 20 ms around it
    boolean[] pause = new boolean[n];
    for (int i = 960; i < n - 960; i++) {
      double max = 0;
      for (int j = i - 960; j <= i + 960; j += 16) max = Math.max(max, Math.abs(voice[j]));
      pause[i] = max < 0.01;
    }
    double noiseIn = 0, noiseOut = 0, voiceIn = 0, voiceOut = 0;
    int pauses = 0, talking = 0;
    for (int i = SR; i < n - 960; i++) {
      boolean silent = pause[i];
      if (silent) { noiseIn += noisy[i] * noisy[i]; noiseOut += out[i] * out[i]; pauses++; }
      else if (Math.abs(voice[i]) > 0.05) { voiceIn += noisy[i] * noisy[i]; voiceOut += out[i + 480] * out[i + 480]; talking++; }
    }
    double noiseDrop = 10 * Math.log10(noiseIn / (noiseOut + 1e-12));
    double voiceDrop = 10 * Math.log10(voiceIn / (voiceOut + 1e-12));
    check(noiseDrop > 15, String.format("RNNoise: ruido en las pausas -%.1f dB", noiseDrop));
    check(voiceDrop < 6, String.format("RNNoise: la voz baja solo %.1f dB", voiceDrop));
  }

  static void shiftTests() {
    int n = 1 << 16;
    for (double f : new double[]{150, 1000, 4000}) {
      FrequencyShifter shifter = new FrequencyShifter(SR, 5);
      float[] x = sine(f, 1, 2 * n);
      shifter.process(x, x.length);
      double[] re = new double[n], im = new double[n];
      for (int i = 0; i < n; i++) re[i] = x[n + i] * (0.5 - 0.5 * Math.cos(2 * Math.PI * i / (n - 1)));
      new Fft(n).transform(re, im);
      double bin = (double) SR / n, up = 0, down = 0;
      for (int k = 0; k < n / 2; k++) {
        double m = Math.hypot(re[k], im[k]);
        if (Math.abs(k * bin - (f + 5)) < 2) up = Math.max(up, m);
        if (Math.abs(k * bin - (f - 5)) < 2) down = Math.max(down, m);
      }
      double image = 20 * Math.log10(down / up);
      check(image < -35, String.format("desplazamiento: %.0f Hz -> %.0f Hz, imagen a %.1f dB", f, f + 5, image));
    }
  }

  // Closed room: five narrow modes, short path, loop gain well above the howl point
  static void harshRoomTests() {
    int n = SR * 10, block = 480, delay = 480;
    float[] voice = speech(n, 1, false);
    double source = levelDb(voice, n / 2);
    double[] modes = {620, 1250, 2300, 3400, 5100};
    String[] names = {"normal", "fuerte"};
    double[] limits = {3.0, 0.0};
    for (int mode = 0; mode < 2; mode++) {
      Biquad[] room = new Biquad[modes.length];
      for (int i = 0; i < modes.length; i++) {
        room[i] = new Biquad();
        room[i].setPeaking(SR, modes[i], 8, 8);
      }
      AudioChain chain = new AudioChain(SR);
      chain.feedbackEnabled = true;
      chain.backoffEnabled  = (mode == 1);
      float[] out = new float[n], work = new float[block];
      short[] pcm = new short[block];
      for (int s = 0; s + block <= n; s += block) {
        for (int i = 0; i < block; i++) {
          int t = s + i;
          float fb = (t >= delay) ? out[t - delay] : 0f;
          for (Biquad b : room) fb = b.process(fb);
          float mic = voice[t] + 0.7f * fb;
          pcm[i] = (short) Math.max(-32768, Math.min(32767, mic * 32768));
        }
        chain.process(pcm, work, block, 1.0f);
        for (int i = 0; i < block; i++) out[s + i] = pcm[i] / 32768f;
      }
      double level = levelDb(out, n / 2) - source;
      check(level < limits[mode], String.format("sala cerrada, protección %s: %+.1f dB sobre la voz (%d cortes)",
        names[mode], level, chain.getFeedbackCuts()));
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
    harshRoomTests();
    denoiseTests();
    shiftTests();

    if (failures > 0) {
      System.out.println(failures + " pruebas fallidas");
      System.exit(1);
    }
  }
}
