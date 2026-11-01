#### Echo Micro (fork)

Fork of [Echo](https://github.com/warren-bank/Android-Pipe-Audio-From-Microphone-To-Speaker): use the phone as a microphone for a Bluetooth or wired (AUX) speaker, with a clean interface and processing against noise and feedback.

Package `io.github.alfiefe10.echo`, so it installs next to the original. Signed APKs (arm64): [Releases](https://github.com/Alfiefe10/echo-micro/releases). Interface in English and Spanish (also selectable per app in Android 13+).

##### Main screen

* current output (Bluetooth, wired, USB adapter or phone speaker)
* big microphone button, in *Toggle* or *Hold* mode, with input level; record button (WAV)
* volume, voice (*Natural*, *Clear*, *Small speaker* equalizer), background noise (*Off*, *Light* = Android suppressor, *Strong* = RNNoise) and howl protection (*Off*, *Normal* = adaptive notches, *Strong* = notches + level back-off)

##### Settings

* **Output**: block output through the phone speaker (on by default, avoids the howl), remember settings per output (Bluetooth / wired), delay and feedback test (clicks played and measured: delay in ms, coupling in dB, howl risk), DAC check
* **Microphone**: source (MIC, VOICE_PERFORMANCE, UNPROCESSED, VOICE_COMMUNICATION), directional mic, noise gate
* **Processing**: low-cut filter, limiter
* **Experimental**: automatic volume, 5 Hz frequency shift
* **Recording**: WAV files in `Recordings/Echo Micro`
* **Language**, **About**

##### Audio chain

Mic (native rate, 10 ms blocks) → high-pass 120 Hz → RNNoise → noise gate → automatic volume → equalizer → volume × back-off → feedback suppressor (FFT peak detection, up to 10 notches) → frequency shift → limiter (-1 dBFS) → output.

The output keeps at most ~30 ms of audio waiting (`dsp/LatencyKeeper`): with a USB adapter the output clock drifts from the mic, and after any hiccup the extra delay would otherwise stay and make the feedback sound like an echo. Quiet blocks are skipped first; any block past 100 ms.

##### Build

* CI (`.github/workflows/build.yml`): downloads RNNoise 0.2 (`scripts/fetch_rnnoise.py`, checked by SHA-256 and patched for NEON), runs the DSP tests, builds and signs. A push to `master` builds; a tag `vX.Y.Z` publishes a release with `echo-micro-X.Y.Z.apk`.
* Signing comes from the secrets `KEYSTORE_B64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`.
* DSP tests in plain Java, with simulated rooms (feedback loop, howls, delay drift):

  ```sh
  python3 scripts/fetch_rnnoise.py
  C=android-studio-project/Echo/src/main/cpp; S=$C/rnnoise/src; mkdir -p out
  gcc -O2 -shared -fPIC -w -I"$JAVA_HOME/include" -I"$JAVA_HOME/include/linux" -I$C/rnnoise/include -I$S \
    $C/rnnoise_jni.c $S/denoise.c $S/rnn.c $S/pitch.c $S/kiss_fft.c $S/celt_lpc.c $S/nnet.c \
    $S/nnet_default.c $S/parse_lpcnet_weights.c $S/rnnoise_data.c $S/rnnoise_tables.c -lm -o out/libechornnoise.so
  javac -d out android-studio-project/Echo/src/main/java/com/github/warren_bank/echo/dsp/*.java dsp-tests/*.java
  java -ea -Djava.library.path=out -cp out DspTests && java -ea -cp out ProbeTests
  ```

##### Versions

* 1.1: main screen · 1.2: phone speaker guard · 1.3: low-cut, limiter, directional mic · 1.4: feedback suppressor · 1.5: RNNoise · 1.6: frequency shift
* 2.0: new interface · 2.1: DAC check · 2.2: loud howls and level back-off, safe insets, language
* 3.0: settings screen, equalizer, noise gate, automatic volume, per-output settings, recording, delay and feedback test
* 3.1: output delay kept from growing

---

#### [Echo](https://github.com/warren-bank/Android-Pipe-Audio-From-Microphone-To-Speaker)

Android app to pipe audio from the microphone to the speaker, with adjustable gain.

#### Warning:

* a feedback loop will occur if your microphone is within audible range of your speaker
  - this is bad
  - don't do this

#### Purpose:

* very much the same as the [Android PTT Bluetooth Speaker](https://github.com/warren-bank/Android-PTT-Bluetooth-Speaker) app that I wrote many years ago
* the difference being:
  - that app uses the microphone on an external Android device, and connects over wireless Bluetooth
  - this app uses the microphone on the same Android device
    * which is assumed to use a wireless microphone that connects using a 2.4 GHz USB dongle

#### Why the Change:

* despite the Bluetooth pair of sender/receiver apps working quite well, Bluetooth is a bit of a pain&hellip;
  - it requires pairing (one-time)
  - manually reconnecting (every time)
* whereas a wireless microphone with 2.4 GHz USB dongle&hellip;
  - connects immediately (every time),<br>which makes it convenient to turn off power on the microphone when not needed
  - has slightly longer range
  - is incredibly small and inexpensive with quite good audio quality

#### Use Case:

* your TV has an Android box connected to its input
* you're wearing Bluetooth headphones to listen to the TV&hellip; oblivious to the world around you
* your family members are constantly trying to talk to you, but you can't hear them
* do you:
  1. pause/mute your video player
  2. take off your headphones&hellip; only to find out that they forgot what they wanted to say
* well, now you have a 3rd choice:
  - plug your 2.4 GHz USB dongle into the Android box
    * multi-port USB hubs work great
    * some have individual on/off switches for each port
  - start this app, which runs a foreground service
    * adjust the gain through the notification&hellip;<br>so the audio isn't too loud when played on your headphones
  - position the wireless microphone near your family members&hellip;<br>depending on its form factor, either:
    * in a central location
      - ex: on a kitchen table
    * in close proximity to each person
      - ex: each family member wears a small microphone clipped to their shirt collar
  - instruct each person to simply turn on their microphone before they attempt to speak to you
    * when the conversation is done, they can turn off their microphone
      - to conserve its battery
      - to not subject you to unwanted household chatter

#### Notes:

* this doesn't mute your TV program
  - you'll hear both audio sources at the same time
* you can choose to manually pause/mute your video player
  - in which case, now you'll only hear the audio from the wireless microphone
  - but, depending on how you've adjusted the gain, this shouldn't be necessary for most interactions

#### Legal:

* copyright: [Warren Bank](https://github.com/warren-bank)
* license: [GPL-2.0](https://www.gnu.org/licenses/old-licenses/gpl-2.0.txt)
