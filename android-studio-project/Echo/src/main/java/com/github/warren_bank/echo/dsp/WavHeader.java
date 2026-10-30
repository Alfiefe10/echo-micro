package com.github.warren_bank.echo.dsp;

// 44-byte canonical RIFF/WAVE header for integer PCM (little endian).
public final class WavHeader {
  public static final int SIZE = 44;
  // RIFF sizes are 32-bit: the data chunk cannot exceed this
  public static final long MAX_DATA_BYTES = 0xFFFFFFFFL - (SIZE - 8);

  private WavHeader() {}

  public static byte[] build(int sampleRate, int channels, int bitsPerSample, long dataBytes) {
    if (sampleRate <= 0 || channels <= 0 || bitsPerSample <= 0 || bitsPerSample % 8 != 0)
      throw new IllegalArgumentException();
    long data = Math.max(0, Math.min(dataBytes, MAX_DATA_BYTES));
    int blockAlign = channels * bitsPerSample / 8;
    byte[] h = new byte[SIZE];
    ascii(h, 0, "RIFF");
    int32(h, 4, 36 + data);
    ascii(h, 8, "WAVE");
    ascii(h, 12, "fmt ");
    int32(h, 16, 16);                // fmt chunk size
    int16(h, 20, 1);                 // PCM
    int16(h, 22, channels);
    int32(h, 24, sampleRate);
    int32(h, 28, (long) sampleRate * blockAlign);  // byte rate
    int16(h, 32, blockAlign);
    int16(h, 34, bitsPerSample);
    ascii(h, 36, "data");
    int32(h, 40, data);
    return h;
  }

  private static void ascii(byte[] b, int off, String s) {
    for (int i = 0; i < 4; i++) b[off + i] = (byte) s.charAt(i);
  }

  private static void int16(byte[] b, int off, int v) {
    b[off] = (byte) v;
    b[off + 1] = (byte) (v >>> 8);
  }

  private static void int32(byte[] b, int off, long v) {
    for (int i = 0; i < 4; i++) b[off + i] = (byte) (v >>> (8 * i));
  }
}
