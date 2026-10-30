import com.github.warren_bank.echo.dsp.*;

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

    if (failures > 0) {
      System.out.println(failures + " pruebas fallidas");
      System.exit(1);
    }
  }
}
