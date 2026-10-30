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

  // Gaussian noise at a given RMS level
  static float[] noise(double db, int n, long seed) {
    Random r = new Random(seed);
    double sd = Math.pow(10, db / 20);
    float[] x = new float[n];
    for (int i = 0; i < n; i++) x[i] = (float) (sd * r.nextGaussian());
    return x;
  }

  static void inBlocks(float[] x, java.util.function.BiConsumer<float[], Integer> f) {
    float[] blk = new float[480];
    for (int s = 0; s + 480 <= x.length; s += 480) {
      System.arraycopy(x, s, blk, 0, 480);
      f.accept(blk, s);
      System.arraycopy(blk, 0, x, s, 480);
    }
  }

  static void gateTests() {
    NoiseGate gate = new NoiseGate(SR);
    float[] bg = noise(-60, SR * 3, 7);
    double before = levelDb(bg, SR);
    inBlocks(bg, (b, s) -> gate.process(b, 480));
    double drop = before - levelDb(bg, SR);
    check(drop >= 20 && !gate.isOpen(), String.format("puerta: ruido a -60 dBFS atenuado %.1f dB", drop));

    NoiseGate g2 = new NoiseGate(SR);
    float[] voice = speech(SR * 6, 5, false), out = voice.clone();
    inBlocks(out, (b, s) -> g2.process(b, 480));
    double loss = levelDb(voice, 0) - levelDb(out, 0);
    check(loss <= 1, String.format("puerta: la voz baja %.2f dB", loss));

    NoiseGate g3 = new NoiseGate(SR);
    float[] x = noise(-60, SR * 2, 8);
    int onset = SR;
    for (int i = onset; i < x.length; i++) x[i] += (float) (0.1 * Math.sin(2 * Math.PI * 1000 * i / SR));
    float[] one = new float[1];
    int opened = -1;
    for (int i = 0; i < x.length && opened < 0; i++) {
      one[0] = x[i];
      g3.process(one, 1);
      if (i >= onset && g3.getGainDb() >= -1) opened = i - onset;
    }
    double ms = opened * 1000.0 / SR;
    check(opened >= 0 && ms < 5, String.format("puerta: abre en %.2f ms", ms));
  }

  static void agcTests() {
    int n = SR * 10;
    float[] voice = speech(n, 6, false);
    double scale = Math.pow(10, (-35 - levelDb(voice, 0)) / 20);
    for (int i = 0; i < n; i++) voice[i] *= scale;
    AutoGain agc = new AutoGain(SR);
    boolean[] talking = new boolean[n / 480];
    double[] sums = new double[2];
    inBlocks(voice, (b, s) -> {
      double e = 0;
      for (int i = 0; i < 480; i++) e += b[i] * b[i];
      boolean v = 10 * Math.log10(e / 480 + 1e-20) > -45;
      talking[s / 480] = v;
      agc.process(b, 480, v);
      if (v && s >= n / 2) {
        for (int i = 0; i < 480; i++) sums[0] += b[i] * b[i];
        sums[1] += 480;
      }
    });
    double outDb = 10 * Math.log10(sums[0] / sums[1]);
    check(Math.abs(outDb + 20) <= 3, String.format("AGC: voz a -35 dBFS -> %.1f dBFS con voz (ganancia %+.1f dB)", outDb, agc.getGainDb()));

    double g = agc.getGainDb();
    float[] quiet = noise(-60, SR * 3, 9);
    inBlocks(quiet, (b, s) -> agc.process(b, 480, false));
    check(agc.getGainDb() == g, String.format("AGC: en silencio la ganancia no sube (%+.2f -> %+.2f dB)", g, agc.getGainDb()));

    AutoGain fresh = new AutoGain(SR);
    float[] q2 = noise(-60, SR * 3, 10);
    inBlocks(q2, (b, s) -> fresh.process(b, 480, false));
    check(fresh.getGainDb() == 0, String.format("AGC: sin voz desde el principio se queda en %+.2f dB", fresh.getGainDb()));
  }

  static double eqDb(int preset, double freq) {
    Equalizer eq = new Equalizer(SR);
    eq.setPreset(preset);
    float[] x = sine(freq, 0.5, SR);
    inBlocks(x, (b, s) -> eq.process(b, 480));
    return rmsDb(x, SR / 2) - rmsDb(sine(freq, 0.5, SR), SR / 2);
  }

  static void eqTests() {
    double flat = eqDb(Equalizer.FLAT, 1000);
    check(Math.abs(flat) < 0.1, String.format("ecualizador: FLAT a 1 kHz %+.2f dB", flat));
    double clear = eqDb(Equalizer.CLEAR_VOICE, 3000);
    check(Math.abs(clear - 4) < 0.5, String.format("ecualizador: CLEAR_VOICE a 3 kHz %+.2f dB", clear));
    double low = eqDb(Equalizer.SMALL_SPEAKER, 80);
    check(low <= -10, String.format("ecualizador: SMALL_SPEAKER a 80 Hz %+.1f dB", low));

    // Changing preset while playing: no jump bigger than the signal's own slope
    Equalizer eq = new Equalizer(SR);
    float[] x = sine(1000, 0.5, SR);
    inBlocks(x, (b, s) -> {
      if (s == SR / 4) eq.setPreset(Equalizer.SMALL_SPEAKER);
      if (s == SR / 2) eq.setPreset(Equalizer.CLEAR_VOICE);
      if (s == 3 * SR / 4) eq.setPreset(Equalizer.FLAT);
      eq.process(b, 480);
    });
    double steady = 0.5 * 2 * Math.PI * 1000 / SR, jump = 0;
    for (int i = 1; i < x.length; i++) jump = Math.max(jump, Math.abs(x[i] - x[i - 1]));
    check(jump < 1.5 * steady, String.format("ecualizador: cambio de preajuste sin clics (salto %.3f, normal %.3f)", jump, steady));
  }

  // Output buffer model: each 10 ms the device plays 480 * rate frames; a stall delivers
  // several blocks at once. Queue sizes are measured right after writing a block. Speech: 300 ms loud, 200 ms quiet (or no pauses at all).
  static long[] runLatency(double rate, int stallAt, int stallBlocks, boolean pauses, int blocks) {
    LatencyKeeper keeper = new LatencyKeeper(SR, 480);
    short[] loud = new short[480], quiet = new short[480];
    Arrays.fill(loud, (short) 12000);
    Arrays.fill(quiet, (short) 200);
    double queued = 0;
    long maxAfter = 0, finalQueued, drops = 0;
    for (int b = 0; b < blocks; b++) {
      int arriving = (b == stallAt) ? stallBlocks : 1;
      queued = Math.max(0, queued - 480 * rate);
      for (int k = 0; k < arriving; k++) {
        boolean speaking = !pauses || (b % 50) < 30;
        if (keeper.shouldDrop((long) queued, speaking ? loud : quiet, 480)) drops++;
        else queued += 480;
      }
      if (b > stallAt + 1 || stallAt < 0) maxAfter = Math.max(maxAfter, (long) queued);
    }
    finalQueued = (long) queued;
    return new long[]{drops, maxAfter, finalQueued};
  }

  static void latencyTests() {
    long[] r = runLatency(1.0, -1, 0, true, 3000);
    check(r[0] == 0 && r[1] <= 960, "retraso: sin deriva no se salta nada (cola max " + r[1] * 1000 / SR + " ms)");
    r = runLatency(0.995, -1, 0, true, 6000);
    check(r[1] <= 2000, "retraso: salida lenta, se mantiene <= 42 ms (max " + r[1] * 1000 / SR + " ms, " + r[0] + " bloques saltados)");
    r = runLatency(1.0, 100, 30, true, 1000);
    check(r[2] <= 2000, "retraso: tras un parón de 300 ms vuelve a <= 42 ms (final " + r[2] * 1000 / SR + " ms)");
    check(r[1] <= SR / 10 + 480, "retraso: tras el parón nunca pasa de 110 ms (max " + r[1] * 1000 / SR + " ms)");
    r = runLatency(0.995, -1, 0, false, 6000);
    check(r[1] <= SR / 10 + 480, "retraso: hablando sin pausas, tope de 110 ms (max " + r[1] * 1000 / SR + " ms)");
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
    gateTests();
    agcTests();
    eqTests();
    latencyTests();

    if (failures > 0) {
      System.out.println(failures + " pruebas fallidas");
      System.exit(1);
    }
  }
}
