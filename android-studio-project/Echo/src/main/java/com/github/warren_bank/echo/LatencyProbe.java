package com.github.warren_bank.echo;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.AudioTrack;
import android.media.MediaRecorder;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;

import com.github.warren_bank.echo.dsp.LatencyAnalyzer;

// Measures the mic -> speaker loop delay and how loud the speaker comes back
// into the mic. Opens the streams like AudioEchoService and runs the same
// read-block / write-block loop, so the delay is the one the echo really has.
// The echo service must be stopped while this runs. Needs RECORD_AUDIO.
public final class LatencyProbe {

  public interface Listener {
    void onResult(Result r);
    void onError(String message);
  }

  public static final class Result {
    public final double  delayMs;
    public final double  feedbackDb;    // dB relative to what was played
    public final double  confidenceDb;  // peak over background
    public final int     pulsesUsed;
    public final int     sampleRate;
    public final boolean reliable;

    Result(LatencyAnalyzer.Result r, int sampleRate) {
      this.delayMs      = r.delayMs;
      this.feedbackDb   = r.feedbackDb;
      this.confidenceDb = r.confidenceDb;
      this.pulsesUsed   = r.pulsesUsed;
      this.sampleRate   = sampleRate;
      this.reliable     = r.reliable;
    }
  }

  // About -12 dBFS before the media volume: audible but not loud
  public static final float AMPLITUDE = 0.25f;

  private static final int DEFAULT_SAMPLE_RATE = 48000;
  private static final int BLOCK_MS = 10;
  private static final int CHANNEL_IN   = AudioFormat.CHANNEL_IN_MONO;
  private static final int CHANNEL_OUT  = AudioFormat.CHANNEL_OUT_MONO;
  private static final int AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT;

  private static final class Run {
    volatile boolean cancelled = false;
  }

  private final Context context;
  private final Handler main = new Handler(Looper.getMainLooper());
  private Run run;
  private Thread thread;

  public LatencyProbe(Context context) {
    this.context = context.getApplicationContext();
  }

  // Starts a measurement; false if one is already running.
  // The listener is called once, on the main thread, unless cancelled.
  public synchronized boolean start(final Listener listener) {
    if (isRunning()) return false;
    final Run current = new Run();
    run = current;
    thread = new Thread(() -> measure(current, listener), "LatencyProbe");
    thread.start();
    return true;
  }

  // Stops the measurement; the listener will not be called. Safe to call any time.
  public synchronized void cancel() {
    if (run != null) run.cancelled = true;
    run = null;
  }

  // True until the audio thread has released everything (also briefly after cancel)
  public synchronized boolean isRunning() {
    return thread != null && thread.isAlive();
  }

  private int getNativeSampleRate() {
    try {
      AudioManager am = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
      String rate = am.getProperty(AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE);
      if (rate != null) return Integer.parseInt(rate);
    }
    catch(Exception ignore) {}
    return DEFAULT_SAMPLE_RATE;
  }

  private void measure(final Run current, final Listener listener) {
    Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO);

    AudioRecord audioRecord = null;
    AudioTrack  audioTrack  = null;
    LatencyAnalyzer.Result analysis = null;
    String error = null;
    final int sampleRate = getNativeSampleRate();

    try {
      LatencyAnalyzer analyzer = new LatencyAnalyzer(sampleRate);
      float[] play = analyzer.buildPlayback(AMPLITUDE);
      int block = sampleRate * BLOCK_MS / 1000;
      int total = ((play.length + block - 1) / block) * block;
      float[] recorded = new float[total];
      short[] in  = new short[block];
      short[] out = new short[block];

      int minRecordBuf = AudioRecord.getMinBufferSize(sampleRate, CHANNEL_IN, AUDIO_FORMAT);
      int minTrackBuf  = AudioTrack.getMinBufferSize(sampleRate, CHANNEL_OUT, AUDIO_FORMAT);
      if (minRecordBuf <= 0 || minTrackBuf <= 0)
        throw new IllegalStateException("buffer size");

      audioRecord = new AudioRecord(
        MediaRecorder.AudioSource.MIC,
        sampleRate,
        CHANNEL_IN,
        AUDIO_FORMAT,
        minRecordBuf * 2
      );
      if (audioRecord.getState() != AudioRecord.STATE_INITIALIZED)
        throw new IllegalStateException("AudioRecord");

      audioTrack = new AudioTrack.Builder()
        .setAudioAttributes(
          new AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()
        )
        .setAudioFormat(
          new AudioFormat.Builder()
            .setEncoding(AUDIO_FORMAT)
            .setSampleRate(sampleRate)
            .setChannelMask(CHANNEL_OUT)
            .build()
        )
        .setBufferSizeInBytes(minTrackBuf * 2)
        .setTransferMode(AudioTrack.MODE_STREAM)
        .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
        .build();
      if (audioTrack.getState() != AudioTrack.STATE_INITIALIZED)
        throw new IllegalStateException("AudioTrack");

      audioRecord.startRecording();
      audioTrack.play();

      // Same loop as the echo: read one block, write one block
      for (int pos = 0; pos < total && !current.cancelled; pos += block) {
        int got = 0;
        while (got < block && !current.cancelled) {
          int r = audioRecord.read(in, got, block - got);
          if (r < 0) throw new IllegalStateException("read " + r);
          got += r;
        }
        for (int i = 0; i < block; i++) {
          recorded[pos + i] = in[i] / 32768f;
          int j = pos + i;
          float v = (j < play.length) ? play[j] : 0f;
          out[i] = (short) Math.round(v * 32767f);
        }
        audioTrack.write(out, 0, block);
      }

      if (!current.cancelled)
        analysis = analyzer.analyze(recorded, total, AMPLITUDE);
    }
    catch(SecurityException e) {
      error = "RECORD_AUDIO permission";
    }
    catch(Exception e) {
      error = (e.getMessage() != null) ? e.getMessage() : e.toString();
    }
    finally {
      if (audioRecord != null) {
        try { audioRecord.stop(); } catch(Exception ignore) {}
        audioRecord.release();
      }
      if (audioTrack != null) {
        try {
          audioTrack.pause();
          audioTrack.flush();
          audioTrack.stop();
        } catch(Exception ignore) {}
        audioTrack.release();
      }
    }

    final LatencyAnalyzer.Result result = analysis;
    final String message = (error == null && result == null) ? "no result" : error;
    main.post(() -> {
      synchronized (LatencyProbe.this) {
        if (current.cancelled) return;
        if (run == current) run = null;
      }
      if (result != null) listener.onResult(new Result(result, sampleRate));
      else listener.onError(message);
    });
  }
}
