#### Echo Micro (fork)

Fork of [Echo](https://github.com/warren-bank/Android-Pipe-Audio-From-Microphone-To-Speaker) with a main screen:

* current output (Bluetooth, wired or phone speaker, with a feedback warning)
* big microphone button, in *Toggle* or *Hold to speak* mode
* gain slider, input level meter and microphone source selector (MIC, VOICE_PERFORMANCE, UNPROCESSED, VOICE_COMMUNICATION)
* audio at the device's native sample rate, to keep the low-latency path

Package `io.github.alfiefe10.echo`, so it installs next to the original. Signed APKs: [Releases](https://github.com/Alfiefe10/echo-micro/releases).

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
