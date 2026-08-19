package com.github.warren_bank.echo;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ServiceInfo;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.AudioTrack;
import android.media.MediaRecorder;
import android.os.Build;
import android.os.IBinder;
import android.os.Process;
import android.preference.PreferenceManager;

public class AudioEchoService extends Service {
  private static final String ACTION_EXIT     = "com.github.warren_bank.echo.ACTION_EXIT";
  private static final String ACTION_VOLUME   = "com.github.warren_bank.echo.ACTION_VOLUME";
  private static final String EXTRA_VOLUME_UP = "EXTRA_VOLUME_UP";
  private static final String CHANNEL_ID      = "AudioEchoService";
  private static final int NOTIFICATION_ID    = 101;
  private static final float audioGainMax     = 2.0f;
  private static final float audioGainStep    = 0.1f;

  private static int convertGainToPercent(float gain) {
    return Math.round(gain * 100.0f);
  }

  private static int convertGainToProgress(float gain) {
    return convertGainToPercent(gain);
  }

  private static final int maxProgress = convertGainToProgress(audioGainMax);

  private static final int SAMPLE_RATE = 44100;
  private static final int CHANNEL_IN = AudioFormat.CHANNEL_IN_MONO;
  private static final int CHANNEL_OUT = AudioFormat.CHANNEL_OUT_MONO;
  private static final int AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT;

  private boolean isRunning;
  private float audioGain; // Adjustable gain multiplier
  private NotificationManager notificationManager;
  private Notification.Builder notificationBuilder;
  private String notificationTitle;
  private Thread audioThread;
  private AudioRecord audioRecord;
  private AudioTrack audioTrack;

  @Override
  public void onCreate() {
    super.onCreate();

    isRunning = false;
    restoreAudioGain();

    notificationManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);

    // Intent for the "Volume Down" action button
    Intent downIntent = new Intent(this, AudioEchoService.class);
    downIntent.setAction(ACTION_VOLUME + "_DOWN");
    downIntent.putExtra(EXTRA_VOLUME_UP, false);
    PendingIntent downPendingIntent = PendingIntent.getService(
      this, 1, downIntent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
    );

    // Intent for the "Volume Up" action button
    Intent upIntent = new Intent(this, AudioEchoService.class);
    upIntent.setAction(ACTION_VOLUME + "_UP");
    upIntent.putExtra(EXTRA_VOLUME_UP, true);
    PendingIntent upPendingIntent = PendingIntent.getService(
      this, 2, upIntent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
    );

    // Intent to Exit when the notification is explicitly cleared by the user
    Intent exitIntent = new Intent(this, AudioEchoService.class);
    exitIntent.setAction(ACTION_EXIT);
    PendingIntent exitPendingIntent = PendingIntent.getService(
      this, 3, exitIntent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
    );

    if (Build.VERSION.SDK_INT < 26) {
      notificationBuilder = new Notification.Builder(this);
    }
    else {
      createNotificationChannel();

      notificationBuilder = new Notification.Builder(this, CHANNEL_ID);
    }

    String volume      = getString(R.string.volume);
    String volume_down = volume + " -";
    String volume_up   = volume + " +";

    notificationTitle  = volume + ": %d%%";

    notificationBuilder
      .setSmallIcon(R.drawable.notification)
      .setContentText(getString(R.string.notification_text))
      .addAction(
        R.drawable.arrow_circle_down,
        volume_down,
        downPendingIntent
      )
      .addAction(
        R.drawable.arrow_circle_up,
        volume_up,
        upPendingIntent
      )
      .addAction(
        R.drawable.exit_to_app,
        getString(R.string.exit),
        exitPendingIntent
      )
      .setOngoing(true)
      .setOnlyAlertOnce(true)
      .setDefaults(0)
      .setPriority(Notification.PRIORITY_MAX);

    audioThread = null;
  }

  private Notification getNotification() {
    return notificationBuilder
      .setContentTitle(String.format(notificationTitle, convertGainToPercent(audioGain)))
      .setProgress(maxProgress, convertGainToProgress(audioGain), false)
      .build();
  }

  private void createNotificationChannel() {
    if (notificationManager == null) return;

    NotificationChannel channel = new NotificationChannel(CHANNEL_ID, CHANNEL_ID, NotificationManager.IMPORTANCE_HIGH);
    notificationManager.createNotificationChannel(channel);
  }

  private void updateNotificationProgress() {
    if (notificationManager == null) return;

    notificationManager.notify(NOTIFICATION_ID, getNotification());
  }

  @Override
  public int onStartCommand(Intent intent, int flags, int startId) {
    onStart(intent, startId);
    return START_STICKY;
  }

  @Override
  public void onStart(Intent intent, int startId) {
    try {
      processIntent(intent);

      if (!isRunning) {
        if (Build.VERSION.SDK_INT < 30)
          startForeground(NOTIFICATION_ID, getNotification());
        else
          startForeground(NOTIFICATION_ID, getNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE);

        startAudioEcho();
      }
    }
    catch(Exception ignore) {}
  }

  private void processIntent(Intent intent) throws Exception {
    if (intent == null) return;

    String action = intent.getAction();
    if (action == null) return;

    if (action.equals(ACTION_VOLUME + "_DOWN") || action.equals(ACTION_VOLUME + "_UP")) {
      if (!intent.hasExtra(EXTRA_VOLUME_UP)) return;

      boolean isVolumeUp = intent.getBooleanExtra(EXTRA_VOLUME_UP, false);
      if (isVolumeUp) {
        if (Float.compare(audioGain, audioGainMax) < 0) {
          audioGain += audioGainStep;
          if (Float.compare(audioGain, audioGainMax) > 0)
            audioGain = audioGainMax;
        }
      }
      else {
        if (Float.compare(audioGain, 0.0f) > 0) {
          audioGain -= audioGainStep;
          if (Float.compare(audioGain, 0.0f) < 0)
            audioGain = 0.0f;
        }
      }

      updateNotificationProgress();
    }
    else if (action.equals(ACTION_EXIT)) {
      if (isRunning)
        isRunning = false;
      else
        stopSelf();

      throw new Exception("exiting");
    }
  }

  private void startAudioEcho() {
    isRunning = true;
    audioThread = new Thread(() -> {
      Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO);

      int minRecordBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_IN, AUDIO_FORMAT);
      int minTrackBuf = AudioTrack.getMinBufferSize(SAMPLE_RATE, CHANNEL_OUT, AUDIO_FORMAT);

      audioRecord = new AudioRecord(
        MediaRecorder.AudioSource.MIC,
        SAMPLE_RATE,
        CHANNEL_IN,
        AUDIO_FORMAT,
        minRecordBuf * 2
      );

      audioTrack = null;
      if (Build.VERSION.SDK_INT < 23) {
        audioTrack = new AudioTrack(
          AudioManager.STREAM_VOICE_CALL,
          SAMPLE_RATE,
          CHANNEL_OUT,
          AUDIO_FORMAT,
          minTrackBuf * 2,
          AudioTrack.MODE_STREAM
        );
      }
      else {
        AudioTrack.Builder builder = new AudioTrack.Builder()
          .setAudioAttributes(
            new AudioAttributes.Builder()
              .setUsage(AudioAttributes.USAGE_MEDIA)
              .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
              .build()
          )
          .setAudioFormat(
            new AudioFormat.Builder()
              .setEncoding(AUDIO_FORMAT)
              .setSampleRate(SAMPLE_RATE)
              .setChannelMask(CHANNEL_OUT)
              .build()
          )
          .setBufferSizeInBytes(minTrackBuf * 2)
          .setTransferMode(AudioTrack.MODE_STREAM);

        if (Build.VERSION.SDK_INT >= 26)
          builder.setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY);

        audioTrack = builder.build();
      }

      short[] buffer = new short[minRecordBuf / 2];
      audioRecord.startRecording();
      audioTrack.play();

      while (isRunning) {
        int readSize = audioRecord.read(buffer, 0, buffer.length);
        if (readSize > 0) {
          // Apply adjustable gain control
          if (audioGain != 1.0f) {
            for (int i = 0; i < readSize; i++) {
              int scaled = (int) (buffer[i] * audioGain);
              // Clamp values to prevent 16-bit PCM integer overflow/distortion
              if (scaled > 32767) scaled = 32767;
              else if (scaled < -32768) scaled = -32768;
              buffer[i] = (short) scaled;
            }
          }
          audioTrack.write(buffer, 0, readSize);
        }
      }

      audioRecord.stop();
      audioRecord.release();
      audioRecord = null;

      audioTrack.pause();
      audioTrack.flush();
      audioTrack.stop();
      audioTrack.release();
      audioTrack = null;

      stopSelf();
    });
    audioThread.start();
  }

  @Override
  public void onDestroy() {
    isRunning = false;
    if (audioThread != null) {
      audioThread.interrupt();
    }
    saveAudioGain();
    super.onDestroy();
  }

  @Override
  public IBinder onBind(Intent intent) {
    return null;
  }

  private void saveAudioGain() {
    SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
    SharedPreferences.Editor editor = prefs.edit();
    editor.putFloat("AUDIO_GAIN", audioGain);
    editor.commit();
  }

  private void restoreAudioGain() {
    SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
    audioGain = prefs.getFloat("AUDIO_GAIN", 1.0f);
  }
}
