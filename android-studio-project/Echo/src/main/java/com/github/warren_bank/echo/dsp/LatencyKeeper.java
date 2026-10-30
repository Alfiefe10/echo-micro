package com.github.warren_bank.echo.dsp;

// Keeps the delay between microphone and speaker from creeping up.
// The mic and the output (above all a USB adapter, which has its own clock) never run at
// exactly the same speed, and any hiccup leaves audio waiting in the output buffer. That
// extra delay never goes away by itself: the speaker repeats the voice later and later,
// and what comes back to the mic sounds like an echo. When too much audio is waiting,
// quiet blocks are skipped; past a hard limit, any block is.
public final class LatencyKeeper {
  private final long targetFrames;
  private final long hardFrames;
  private static final int QUIET_PEAK = 1000;  // about -30 dBFS
  private long dropped = 0;

  public LatencyKeeper(int sampleRate, int blockFrames) {
    targetFrames = 3L * blockFrames;          // 30 ms with 10 ms blocks
    hardFrames   = Math.max(targetFrames * 2, sampleRate / 10L);  // 100 ms
  }

  // queuedFrames: audio already written and not played yet
  public boolean shouldDrop(long queuedFrames, short[] block, int n) {
    if (queuedFrames <= targetFrames) return false;
    if (queuedFrames <= hardFrames) {
      for (int i = 0; i < n; i++) {
        int a = Math.abs(block[i]);
        if (a > QUIET_PEAK) return false;
      }
    }
    dropped++;
    return true;
  }

  public long getDroppedBlocks() {
    return dropped;
  }
}
