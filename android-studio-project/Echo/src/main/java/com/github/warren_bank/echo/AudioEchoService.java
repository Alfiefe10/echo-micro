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
import android.media.AudioDeviceInfo;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.AudioTrack;
import android.media.MediaRecorder;
import android.media.MicrophoneDirection;
import android.media.audiofx.NoiseSuppressor;
import android.os.Build;
import android.os.IBinder;
import android.os.Process;
import android.preference.PreferenceManager;

import com.github.warren_bank.echo.dsp.AudioChain;

public class AudioEchoService extends Service {
  public  static final String ACTION_EXIT     = "com.github.warren_bank.echo.ACTION_EXIT";
  private static final String ACTION_VOLUME   = "com.github.warren_bank.echo.ACTION_VOLUME";
  public  static final String ACTION_SET_GAIN = "com.github.warren_bank.echo.ACTION_SET_GAIN";
  public  static final String ACTION_MUTE     = "com.github.warren_bank.echo.ACTION_MUTE";
  private static final String EXTRA_VOLUME_UP = "EXTRA_VOLUME_UP";
  public  static final String EXTRA_GAIN      = "EXTRA_GAIN";
  public  static final String EXTRA_MUTED     = "EXTRA_MUTED";
  private static final String CHANNEL_ID      = "AudioEchoService";
  private static final int NOTIFICATION_ID    = 101;
  public  static final float audioGainMax     = 2.0f;
  private static final float audioGainStep    = 0.1f;

  public static final String PREF_AUDIO_GAIN   = "AUDIO_GAIN";
  public static final String PREF_AUDIO_SOURCE = "AUDIO_SOURCE";
  public static final String PREF_GUARD_SPEAKER = "GUARD_SPEAKER";
  public static final String PREF_HIGH_PASS     = "HIGH_PASS";
  public static final String PREF_LIMITER       = "LIMITER";
  public static final String PREF_MIC_DIRECTION = "MIC_DIRECTION";
  public static final String PREF_SYSTEM_NS     = "SYSTEM_NS";
  public static final String PREF_FEEDBACK      = "FEEDBACK";
  public static final String PREF_DENOISE       = "DENOISE";
  public static final String PREF_SHIFT         = "FREQUENCY_SHIFT";

  // 10 ms at 48 kHz: small blocks keep the delay down
  private static final int BLOCK_MS = 10;

  // State shared with MainActivity (same process).
  public static volatile boolean isActive    = false;
  public static volatile boolean isMuted     = false;
  public static volatile float   inputLevel  = 0.0f;
  public static volatile AudioDeviceInfo routedDevice = null;
  public static volatile String  lastError   = null;
  // Silence the output while it plays on the phone's own speaker (feedback howl)
  public static volatile boolean guardSpeaker = true;
  public static volatile boolean isBlocked    = false;
  // Processing switches, applied live
  public static volatile boolean highPass     = true;
  public static volatile boolean limiter      = true;
  public static volatile boolean feedback     = true;
  public static volatile int     feedbackCuts = 0;
  public static volatile boolean denoise      = false;
  public static volatile boolean shift        = false;
  // False when RNNoise cannot run here (library missing or not 48 kHz)
  public static volatile boolean denoiseUsable = true;

  public static boolean isPhoneSpeaker(AudioDeviceInfo device) {
    if (device == null) return false;
    int type = device.getType();
    return type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
        || type == AudioDeviceInfo.TYPE_BUILTIN_EARPIECE
        || type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER_SAFE;
  }

  private static int convertGainToPercent(float gain) {
    return Math.round(gain * 100.0f);
  }

  private static int convertGainToProgress(float gain) {
    return convertGainToPercent(gain);
  }

  private static final int maxProgress = convertGainToProgress(audioGainMax);

  private static final int DEFAULT_SAMPLE_RATE = 48000;
  private static final int CHANNEL_IN = AudioFormat.CHANNEL_IN_MONO;
  private static final int CHANNEL_OUT = AudioFormat.CHANNEL_OUT_MONO;
  private static final int AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT;

  // Each audio thread owns its own flag, so a thread that is still shutting down
  // cannot stop a newer one.
  private static final class Session {
    volatile boolean running = true;
  }

  private Session session;
  private float audioGain; // Adjustable gain multiplier
  private NotificationManager notificationManager;
  private Notification.Builder notificationBuilder;
  private String notificationTitle;
  private Thread audioThread;

  @Override
  public void onCreate() {
    super.onCreate();

    session = null;
    restoreAudioGain();
    loadSettings(this);

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

    // Tapping the notification opens the main screen
    Intent openIntent = new Intent(this, MainActivity.class);
    openIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
    PendingIntent openPendingIntent = PendingIntent.getActivity(
      this, 4, openIntent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
    );

    createNotificationChannel();
    notificationBuilder = new Notification.Builder(this, CHANNEL_ID);

    String volume      = getString(R.string.volume);
    String volume_down = volume + " -";
    String volume_up   = volume + " +";

    notificationTitle  = volume + ": %d%%";

    notificationBuilder
      .setSmallIcon(R.drawable.notification)
      .setContentText(getString(R.string.notification_text))
      .setContentIntent(openPendingIntent)
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
    return START_NOT_STICKY;
  }

  @Override
  public void onStart(Intent intent, int startId) {
    try {
      processIntent(intent);

      if (session == null) {
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

    if (intent.hasExtra(EXTRA_MUTED))
      isMuted = intent.getBooleanExtra(EXTRA_MUTED, false);

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

      saveAudioGain();
      updateNotificationProgress();
    }
    else if (action.equals(ACTION_SET_GAIN)) {
      float gain = intent.getFloatExtra(EXTRA_GAIN, audioGain);
      audioGain = Math.max(0.0f, Math.min(audioGainMax, gain));
      saveAudioGain();
      updateNotificationProgress();
    }
    else if (action.equals(ACTION_EXIT)) {
      if (session != null) {
        session.running = false;
        session = null;
      }
      isActive = false;
      stopForeground(true);
      stopSelf();

      throw new Exception("exiting");
    }
  }

  public static void loadSettings(Context context) {
    SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
    guardSpeaker = prefs.getBoolean(PREF_GUARD_SPEAKER, true);
    highPass     = prefs.getBoolean(PREF_HIGH_PASS, true);
    limiter      = prefs.getBoolean(PREF_LIMITER, true);
    feedback     = prefs.getBoolean(PREF_FEEDBACK, true);
    denoise      = prefs.getBoolean(PREF_DENOISE, false);
    shift        = prefs.getBoolean(PREF_SHIFT, false);
  }

  private int getNativeSampleRate() {
    try {
      AudioManager am = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
      String rate = am.getProperty(AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE);
      if (rate != null) return Integer.parseInt(rate);
    }
    catch(Exception ignore) {}
    return DEFAULT_SAMPLE_RATE;
  }

  private int getAudioSource() {
    SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
    return prefs.getInt(PREF_AUDIO_SOURCE, MediaRecorder.AudioSource.MIC);
  }

  private void startAudioEcho() {
    final Session current = new Session();
    session    = current;
    isActive   = true;
    lastError  = null;
    inputLevel = 0.0f;
    feedbackCuts = 0;

    final int sampleRate  = getNativeSampleRate();
    final int audioSource = getAudioSource();
    SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
    final boolean micDirection = prefs.getBoolean(PREF_MIC_DIRECTION, true);
    final boolean systemNs     = prefs.getBoolean(PREF_SYSTEM_NS, false);

    audioThread = new Thread(() -> {
      Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO);

      AudioRecord audioRecord = null;
      AudioTrack  audioTrack  = null;
      NoiseSuppressor noiseSuppressor = null;
      AudioChain chain = null;

      try {
        int minRecordBuf = AudioRecord.getMinBufferSize(sampleRate, CHANNEL_IN, AUDIO_FORMAT);
        int minTrackBuf  = AudioTrack.getMinBufferSize(sampleRate, CHANNEL_OUT, AUDIO_FORMAT);

        audioRecord = new AudioRecord(
          audioSource,
          sampleRate,
          CHANNEL_IN,
          AUDIO_FORMAT,
          minRecordBuf * 2
        );
        if (audioRecord.getState() != AudioRecord.STATE_INITIALIZED)
          throw new IllegalStateException("AudioRecord");

        // Ask for the mic facing the person who speaks; support depends on the phone
        if (micDirection && Build.VERSION.SDK_INT >= 29) {
          audioRecord.setPreferredMicrophoneDirection(MicrophoneDirection.MIC_DIRECTION_TOWARDS_USER);
          audioRecord.setPreferredMicrophoneFieldDimension(1.0f);
        }

        if (systemNs && NoiseSuppressor.isAvailable()) {
          noiseSuppressor = NoiseSuppressor.create(audioRecord.getAudioSessionId());
          if (noiseSuppressor != null) noiseSuppressor.setEnabled(true);
        }

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

        chain = new AudioChain(sampleRate);
        denoiseUsable = chain.isDenoiseUsable();
        short[] buffer = new short[sampleRate * BLOCK_MS / 1000];
        float[] work   = new float[buffer.length];
        audioRecord.startRecording();
        audioTrack.play();
        routedDevice = audioTrack.getRoutedDevice();

        int frames = 0;
        while (current.running) {
          int readSize = audioRecord.read(buffer, 0, buffer.length);
          if (readSize > 0) {
            // The route can change mid-session (e.g. a Bluetooth speaker disconnects)
            if ((frames++ % 10) == 0)
              routedDevice = audioTrack.getRoutedDevice();
            // Until the route is known, assume the worst
            isBlocked = guardSpeaker && (routedDevice == null || isPhoneSpeaker(routedDevice));

            chain.highPassEnabled = highPass;
            chain.limiterEnabled  = limiter;
            chain.feedbackEnabled = feedback;
            chain.denoiseEnabled  = denoise;
            chain.shiftEnabled    = shift;
            float gain = (isMuted || isBlocked) ? 0.0f : audioGain;
            inputLevel = chain.process(buffer, work, readSize, gain);
            feedbackCuts = chain.getFeedbackCuts();
            audioTrack.write(buffer, 0, readSize);
          }
          else if (readSize < 0) {
            throw new IllegalStateException("read " + readSize);
          }
        }
      }
      catch(Exception e) {
        lastError = e.getMessage();
      }
      finally {
        if (noiseSuppressor != null) noiseSuppressor.release();
        if (chain != null) chain.release();
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
        inputLevel = 0.0f;
        isBlocked  = false;
      }

      // Only the session that is still current may stop the service
      if (session == current) {
        session = null;
        isActive = false;
        routedDevice = null;
        stopSelf();
      }
    });
    audioThread.start();
  }

  @Override
  public void onDestroy() {
    if (session != null) {
      session.running = false;
      session = null;
    }
    isActive = false;
    routedDevice = null;
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
    editor.putFloat(PREF_AUDIO_GAIN, audioGain);
    editor.apply();
  }

  private void restoreAudioGain() {
    SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
    audioGain = prefs.getFloat(PREF_AUDIO_GAIN, 1.0f);
  }
}
