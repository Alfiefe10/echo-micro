import com.github.warren_bank.echo.dsp.LatencyAnalyzer;
import com.github.warren_bank.echo.dsp.WavHeader;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.Random;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;

// Plain-Java checks of the delay/feedback meter and the WAV header.
// Run: javac -d out <dsp sources> ProbeTests.java && java -ea -cp out ProbeTests
public class ProbeTests {
  static int failures = 0;
  static final float AMP = 0.25f;  // what LatencyProbe plays

  static void check(boolean ok, String what) {
    System.out.println((ok ? "OK    " : "FALLO ") + what);
    if (!ok) failures++;
  }

  // Room: direct path + early reflections + exponential diffuse tail + noise (+ hum)
  static float[] room(float[] x, int sr, double delayMs, double gainDb, boolean invert,
                      double noiseDbfs, double rt60, long seed) {
    Random rnd = new Random(seed);
    int d = (int) Math.round(delayMs * sr / 1000.0);
    double g = Math.pow(10, gainDb / 20) * (invert ? -1 : 1);
    int tailLen = (int) (rt60 * sr);
    double[] h = new double[d + tailLen];
    h[d] = g;
    h[d + sr * 3 / 1000] += 0.5 * g;    // early reflections
    h[d + sr * 7 / 1000] += -0.35 * g;
    double gTail = Math.abs(g) * Math.sqrt(0.5 / (rt60 * sr / 13.8));  // tail ~ -3 dB vs direct
    for (int i = sr * 10 / 1000; i < tailLen; i++)
      h[d + i] += gTail * rnd.nextGaussian() * Math.exp(-6.9 * i / (rt60 * sr));

    float[] y = new float[x.length];
    for (int n = 0; n < x.length; n++) {
      if (x[n] == 0) continue;
      for (int k = 0; k < h.length && n + k < y.length; k++) {
        if (h[k] != 0) y[n + k] += (float) (x[n] * h[k]);
      }
    }
    double sigma = Math.pow(10, noiseDbfs / 20);
    for (int n = 0; n < y.length; n++)
      y[n] += (float) (sigma * rnd.nextGaussian() + 0.3 * sigma * Math.sin(2 * Math.PI * 50 * n / sr));
    return y;
  }

  static void roomCase(String name, int sr, double delayMs, double gainDb, boolean invert,
                       double noiseDbfs, long seed) {
    LatencyAnalyzer a = new LatencyAnalyzer(sr);
    float[] play = a.buildPlayback(AMP);
    float[] rec  = room(play, sr, delayMs, gainDb, invert, noiseDbfs, 0.4, seed);
    LatencyAnalyzer.Result r = a.analyze(rec, rec.length, AMP);
    System.out.println("      " + name + ": " + r);
    check(r.reliable, name + ": fiable");
    check(Math.abs(r.delayMs - delayMs) <= 1.0, name + ": retardo " + delayMs + " ms (+-1 ms)");
    check(Math.abs(r.feedbackDb - gainDb) <= 3.0, name + ": nivel " + gainDb + " dB (+-3 dB)");
  }

  static void testRooms() {
    roomCase("sala 37 ms / -18 dB", 48000, 37, -18, false, -50, 1);
    roomCase("sala 37 ms / -18 dB, otra semilla", 48000, 37, -18, false, -50, 7);
    roomCase("polaridad invertida 120 ms / -30 dB, ruido -45 dBFS", 48000, 120, -30, true, -45, 2);
    roomCase("Bluetooth 300 ms / -12 dB", 48000, 300, -12, false, -50, 3);
    roomCase("44,1 kHz 37 ms / -18 dB", 44100, 37, -18, false, -50, 4);
    roomCase("acople debil 25 ms / -40 dB, ruido -55 dBFS", 48000, 25, -40, false, -55, 5);
  }

  static void testNoiseOnly() {
    LatencyAnalyzer a = new LatencyAnalyzer(48000);
    for (long seed = 10; seed < 15; seed++) {
      Random rnd = new Random(seed);
      float[] rec = new float[a.getTotalSamples()];
      for (int i = 0; i < rec.length; i++) rec[i] = (float) (0.01 * rnd.nextGaussian());
      LatencyAnalyzer.Result r = a.analyze(rec, rec.length, AMP);
      System.out.println("      solo ruido (semilla " + seed + "): " + r);
      check(!r.reliable, "solo ruido -> reliable=false (semilla " + seed + ")");
    }
    float[] zeros = new float[a.getTotalSamples()];
    LatencyAnalyzer.Result r = a.analyze(zeros, zeros.length, AMP);
    check(!r.reliable && !Double.isNaN(r.delayMs) && !Double.isNaN(r.feedbackDb),
          "silencio -> reliable=false y sin NaN");
    r = a.analyze(zeros, 1000, AMP);
    check(!r.reliable, "grabacion demasiado corta -> reliable=false");
  }

  static void testSignal() {
    LatencyAnalyzer a = new LatencyAnalyzer(48000);
    float[] p = a.getPulse();
    check(p.length == 960, "pulso de 20 ms a 48 kHz (" + p.length + " muestras)");
    float[] play = a.buildPlayback(AMP);
    float peak = 0;
    for (float v : play) peak = Math.max(peak, Math.abs(v));
    check(peak <= AMP && peak > 0.9f * AMP, "pico de la prueba = amplitud pedida (" + peak + ")");
    check(play.length == a.getTotalSamples(), "longitud de la prueba = getTotalSamples()");
    System.out.println("      prueba: " + (play.length * 1000 / 48000) + " ms, retardo maximo "
                       + (a.getMaxDelaySamples() * 1000 / 48000) + " ms");
  }

  static int le16(byte[] b, int o) { return (b[o] & 0xff) | (b[o + 1] & 0xff) << 8; }
  static long le32(byte[] b, int o) {
    return (b[o] & 0xffL) | (b[o + 1] & 0xffL) << 8 | (b[o + 2] & 0xffL) << 16 | (b[o + 3] & 0xffL) << 24;
  }

  static void testWavHeader() throws Exception {
    byte[] h = WavHeader.build(48000, 1, 16, 96000);
    check(h.length == 44, "cabecera de 44 bytes");
    check(new String(h, 0, 4, "US-ASCII").equals("RIFF") && new String(h, 8, 8, "US-ASCII").equals("WAVEfmt ")
          && new String(h, 36, 4, "US-ASCII").equals("data"), "etiquetas RIFF/WAVE/fmt/data");
    check(le32(h, 4) == 36 + 96000 && le32(h, 40) == 96000, "tamanos RIFF y data");
    check(le16(h, 20) == 1 && le16(h, 22) == 1 && le32(h, 24) == 48000 && le32(h, 28) == 96000
          && le16(h, 32) == 2 && le16(h, 34) == 16, "formato PCM 16 mono 48 kHz");
    byte[] big = WavHeader.build(48000, 1, 16, 1L << 33);
    check(le32(big, 40) == WavHeader.MAX_DATA_BYTES && le32(big, 4) == 0xFFFFFFFFL, "tamano limitado a 4 GB");

    // Real file: header + 0.5 s of a 1 kHz tone, read back with javax.sound
    int n = 24000;
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    out.write(WavHeader.build(48000, 1, 16, n * 2L));
    for (int i = 0; i < n; i++) {
      short s = (short) (10000 * Math.sin(2 * Math.PI * 1000 * i / 48000));
      out.write(s & 0xff); out.write((s >> 8) & 0xff);
    }
    byte[] wav = out.toByteArray();
    AudioInputStream in = AudioSystem.getAudioInputStream(new ByteArrayInputStream(wav));
    AudioFormat f = in.getFormat();
    check(f.getSampleRate() == 48000f && f.getChannels() == 1 && f.getSampleSizeInBits() == 16
          && !f.isBigEndian() && in.getFrameLength() == n, "javax.sound lee el WAV (" + f + ", "
          + in.getFrameLength() + " tramas)");
    byte[] back = new byte[n * 2];
    int got = 0, r;
    while (got < back.length && (r = in.read(back, got, back.length - got)) > 0) got += r;
    short s100 = (short) le16(back, 200);
    check(got == n * 2 && s100 == (short) (10000 * Math.sin(2 * Math.PI * 1000 * 100 / 48000)),
          "muestras intactas");
  }

  public static void main(String[] args) throws Exception {
    testSignal();
    testRooms();
    testNoiseOnly();
    testWavHeader();
    System.out.println(failures == 0 ? "\nTodo bien." : "\nFallos: " + failures);
    if (failures > 0) System.exit(1);
  }
}
