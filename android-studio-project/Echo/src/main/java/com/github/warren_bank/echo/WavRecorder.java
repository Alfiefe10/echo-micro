package com.github.warren_bank.echo;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import android.provider.MediaStore;

import com.github.warren_bank.echo.dsp.WavHeader;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

// Saves what goes to the speaker as a 16-bit mono WAV.
// API 31+: Recordings/Echo Micro, API 29-30: Music/Echo Micro (MediaStore, no permission);
// API 26-28: app-specific Music dir. write() is safe on the audio thread: it only copies
// the block into a pooled buffer and queues it; a writer thread does all the I/O.
public final class WavRecorder {

  public static final String FOLDER = "Echo Micro";

  public static final class Result {
    public final Uri    uri;          // content:// (API 29+) or file://
    public final String path;         // for display, e.g. "Recordings/Echo Micro/echo-...wav"
    public final long   durationMs;   // audio actually written
    public final int    droppedBlocks;
    public final String error;        // null if everything was written

    Result(Uri uri, String path, long durationMs, int droppedBlocks, String error) {
      this.uri = uri;
      this.path = path;
      this.durationMs = durationMs;
      this.droppedBlocks = droppedBlocks;
      this.error = error;
    }
  }

  private static final int QUEUE_BLOCKS = 300;   // ~3 s of 10 ms blocks
  private static final int BLOCK_SAMPLES = 960;  // pooled size; grows if a bigger block comes

  private static final class Block {
    short[] data = new short[BLOCK_SAMPLES];
    int n;
  }

  private static final class Session {
    final ArrayBlockingQueue<Block> free   = new ArrayBlockingQueue<>(QUEUE_BLOCKS);
    final ArrayBlockingQueue<Block> filled = new ArrayBlockingQueue<>(QUEUE_BLOCKS);
    final AtomicInteger dropped = new AtomicInteger();
    final int sampleRate;
    final long startedAt = SystemClock.elapsedRealtime();
    volatile boolean accepting = true;
    volatile boolean stopping  = false;
    // Output (owned by the writer thread until it ends)
    Uri uri;
    String path;
    File file;                    // API 26-28
    ParcelFileDescriptor pfd;     // API 29+
    FileOutputStream fos;
    RandomAccessFile raf;
    FileChannel channel;
    long dataBytes;
    String error;
    Thread writer;

    Session(int sampleRate) {
      this.sampleRate = sampleRate;
      for (int i = 0; i < QUEUE_BLOCKS; i++) free.add(new Block());
    }
  }

  private Context context;
  private volatile Session session;

  public boolean isRecording() {
    return session != null;
  }

  public long getElapsedMs() {
    Session s = session;
    return (s == null) ? 0 : SystemClock.elapsedRealtime() - s.startedAt;
  }

  // Creates the file and starts the writer thread. Call from a normal thread (does I/O).
  public synchronized void start(Context context, int sampleRate) throws IOException {
    if (session != null) throw new IllegalStateException("already recording");
    this.context = context.getApplicationContext();
    Session s = new Session(sampleRate);
    open(s);
    try {
      s.channel.write(ByteBuffer.wrap(WavHeader.build(sampleRate, 1, 16, 0)), 0);
      s.channel.position(WavHeader.SIZE);
    }
    catch(IOException e) {
      closeQuietly(s);
      discard(s);
      throw e;
    }
    s.writer = new Thread(() -> drain(s), "WavRecorder");
    s.writer.start();
    session = s;
  }

  // Non-blocking, no allocation in the normal case; drops the block if the writer is behind.
  public void write(short[] pcm, int n) {
    Session s = session;
    if (s == null || !s.accepting || n <= 0) return;
    Block b = s.free.poll();
    if (b == null) {
      s.dropped.incrementAndGet();
      return;
    }
    if (b.data.length < n) b.data = new short[n];
    System.arraycopy(pcm, 0, b.data, 0, n);
    b.n = n;
    if (!s.filled.offer(b)) s.dropped.incrementAndGet();
  }

  // Flushes the queue, fixes the header and publishes the file. Returns null if not recording.
  // Blocks until the writer has finished (normally a few ms).
  public synchronized Result stop() {
    Session s = session;
    if (s == null) return null;
    session = null;
    s.accepting = false;
    s.stopping  = true;
    try { s.writer.join(); }
    catch(InterruptedException e) { Thread.currentThread().interrupt(); }

    try {
      s.channel.write(ByteBuffer.wrap(WavHeader.build(s.sampleRate, 1, 16, s.dataBytes)), 0);
      s.channel.force(true);
    }
    catch(IOException e) {
      if (s.error == null) s.error = e.toString();
    }
    closeQuietly(s);

    if (s.dataBytes == 0) {
      discard(s);
      return new Result(null, null, 0, s.dropped.get(), (s.error != null) ? s.error : "empty");
    }
    if (Build.VERSION.SDK_INT >= 29) {
      try {
        ContentValues values = new ContentValues();
        values.put(MediaStore.MediaColumns.IS_PENDING, 0);
        context.getContentResolver().update(s.uri, values, null, null);
      }
      catch(Exception e) {
        if (s.error == null) s.error = e.toString();
      }
    }
    long durationMs = s.dataBytes / 2 * 1000 / s.sampleRate;
    return new Result(s.uri, s.path, durationMs, s.dropped.get(), s.error);
  }

  private void drain(Session s) {
    ByteBuffer bytes = ByteBuffer.allocate(BLOCK_SAMPLES * 2).order(ByteOrder.LITTLE_ENDIAN);
    while (true) {
      Block b;
      try { b = s.filled.poll(20, TimeUnit.MILLISECONDS); }
      catch(InterruptedException e) { b = null; }
      if (b == null) {
        if (s.stopping && s.filled.isEmpty()) break;
        continue;
      }
      if (s.error == null) {
        long len = b.n * 2L;
        if (s.dataBytes + len > WavHeader.MAX_DATA_BYTES) {
          s.dropped.incrementAndGet();
        }
        else {
          if (bytes.capacity() < len) bytes = ByteBuffer.allocate((int) len).order(ByteOrder.LITTLE_ENDIAN);
          bytes.clear();
          bytes.asShortBuffer().put(b.data, 0, b.n);
          bytes.limit((int) len);
          try {
            while (bytes.hasRemaining()) s.channel.write(bytes);
            s.dataBytes += len;
          }
          catch(IOException e) {
            s.error = e.toString();  // keep draining so the pool is not exhausted
          }
        }
      }
      s.free.offer(b);
    }
  }

  private void open(Session s) throws IOException {
    String name = "echo-" + new SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(new Date()) + ".wav";

    if (Build.VERSION.SDK_INT >= 29) {
      // "Recordings" is only accepted for audio from API 31 (Environment.DIRECTORY_RECORDINGS)
      String dir = ((Build.VERSION.SDK_INT >= 31) ? Environment.DIRECTORY_RECORDINGS : Environment.DIRECTORY_MUSIC)
        + "/" + FOLDER;
      ContentValues values = new ContentValues();
      values.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
      values.put(MediaStore.MediaColumns.MIME_TYPE, "audio/x-wav");
      values.put(MediaStore.MediaColumns.RELATIVE_PATH, dir);
      values.put(MediaStore.MediaColumns.IS_PENDING, 1);
      ContentResolver resolver = context.getContentResolver();
      Uri collection = MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY);
      s.uri = resolver.insert(collection, values);
      if (s.uri == null) throw new IOException("MediaStore insert failed");
      s.path = dir + "/" + name;
      try {
        s.pfd = resolver.openFileDescriptor(s.uri, "rw");
        if (s.pfd == null) throw new IOException("openFileDescriptor");
        s.fos = new FileOutputStream(s.pfd.getFileDescriptor());
        s.channel = s.fos.getChannel();
      }
      catch(IOException | RuntimeException e) {
        closeQuietly(s);
        discard(s);
        throw (e instanceof IOException) ? (IOException) e : new IOException(e);
      }
    }
    else {
      File dir = context.getExternalFilesDir(Environment.DIRECTORY_MUSIC);
      if (dir == null) throw new IOException("external storage not available");
      if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("mkdirs " + dir);
      File f = new File(dir, name);
      for (int i = 1; f.exists(); i++) f = new File(dir, name.replace(".wav", " (" + i + ").wav"));
      s.file = f;
      s.raf = new RandomAccessFile(f, "rw");
      s.raf.setLength(0);
      s.channel = s.raf.getChannel();
      s.uri = Uri.fromFile(f);
      s.path = f.getAbsolutePath();
    }
  }

  private static void closeQuietly(Session s) {
    try { if (s.channel != null) s.channel.close(); } catch(Exception ignore) {}
    try { if (s.fos != null) s.fos.close(); } catch(Exception ignore) {}
    try { if (s.raf != null) s.raf.close(); } catch(Exception ignore) {}
    try { if (s.pfd != null) s.pfd.close(); } catch(Exception ignore) {}
  }

  // Removes a file that should not be kept (empty or failed to open)
  private void discard(Session s) {
    try {
      if (Build.VERSION.SDK_INT >= 29 && s.uri != null)
        context.getContentResolver().delete(s.uri, null, null);
      else if (s.file != null)
        s.file.delete();
    }
    catch(Exception ignore) {}
  }
}
