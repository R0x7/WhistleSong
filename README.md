# WhistleSong 🎶

An Android app: whistle part of a melody, and it **autotunes** what you whistled and
**composes the rest of the song** in the style you pick.

| Style | Sound |
|---|---|
| 👾 8-Bit | 25% pulse lead, NES-style triangle bass, 16th-note arpeggios, noise drums, bit-crush |
| 🎸 Rock | Overdriven lead guitar, palm-muted power chords, picked bass, live kit with fills & crashes |
| ☕ Lo-Fi | FM electric piano 7th chords, sub bass, lazy swung drums, low-pass + vinyl crackle |
| 🌆 Synthwave | Detuned saw lead, saw pad, octave-pulsing bass, four-on-the-floor + gated clap, delay |
| 🎷 Jazz | Vibes lead, piano comping, walking upright bass, swung ride, chromatic approach notes |
| 🎠 Music Box | FM bell melody, broken-chord accompaniment, echo |

## How it works

Everything runs **on-device and offline**: no network, no API keys.

1. **Pitch tracking**: `dsp/PitchDetector.kt` runs the YIN algorithm on the mic signal
   (tuned to the 350–4200 Hz whistling range). You see the live note while you whistle.
2. **Note segmentation**: `music/Transcriber.kt` median-filters the pitch track, splits notes
   on stable pitch changes and on loudness dips (so repeated notes stay separate), and drops blips.
3. **Autotune**:
   * removes your global tuning offset (most people whistle consistently sharp or flat),
   * finds the key (Krumhansl-Kessler profiles + a penalty for keys that don't contain your notes),
   * snaps every note to the scale,
   * estimates tempo and quantises the rhythm to a 16th-note grid.
4. **Composing the rest**: `music/Composer.kt`
   * learns a variable-order Markov model of *your* melodic intervals and rhythms,
   * blends it with a per-style interval prior and music-theory rules: chord tones on strong
     beats, leap recovery, range limits, pentatonic avoidance in rock, chromatic approach tones in jazz,
   * develops your motif in phrases: **Answer** (sequence / new ending / inversion) →
     **New idea** (fresh material over a style chord progression) → **Return** (your theme
     again), ending on a cadence to the tonic,
   * harmonises everything with one chord per bar.
5. **Arranging and synthesis**: `synth/Arranger.kt` writes bass, chords and drums for the style;
   `synth/Synth.kt` is a small software synth (polyBLEP saws, pulse, FM, Karplus-Strong, synthesized
   drums, delay / low-pass / crackle / bit-crush) that renders the song and plays it through `AudioTrack`.

**🎲 New idea** re-composes with a new random seed. Changing the style re-composes too, since each
style writes differently. **Share WAV** exports the song.

## Build

Requires the Android SDK (API 35) and JDK 17+.

```bash
./gradlew assembleDebug
```

The APK is written to `app/build/outputs/apk/debug/app-debug.apk`. Install it with:

```bash
adb install app/build/outputs/apk/debug/app-debug.apk
```

### Tests

```bash
./gradlew testDebugUnitTest
```

The JVM tests synthesise a detuned, vibrato-laden "whistle" of a known melody, check that it
transcribes back exactly, then compose and render a song in every style. The renders are written
to `app/build/test-songs/*.wav` so you can hear them without a phone.

## Releases (CI)

`.github/workflows/release.yml` builds a release APK and publishes it as a GitHub Release.

* **Tag push:** `git tag v1.0.0 && git push origin v1.0.0` publishes "WhistleSong v1.0.0" with
  `WhistleSong-v1.0.0.apk` attached.
* **Manual run:** Actions → *Release APK* → *Run workflow* and enter a version. The tag is created for you.

`versionName` comes from the tag, and `versionCode` comes from the workflow run number, so it always goes up.

### Signing (recommended)

Without signing secrets, CI signs with a throwaway debug key. That APK installs fine, but each
release has a different key, so Android won't install it as an update. You'd have to uninstall the old
version first. To sign every release with the same key, make a keystore once:

```bash
keytool -genkeypair -keystore whistlesong.jks -alias whistlesong -keyalg RSA -keysize 2048 -validity 10000
```

Then add these repository secrets (Settings → Secrets and variables → Actions):

| Secret | Value |
|---|---|
| `SIGNING_KEYSTORE_BASE64` | output of `base64 -w0 whistlesong.jks` |
| `SIGNING_STORE_PASSWORD` | keystore password |
| `SIGNING_KEY_ALIAS` | `whistlesong` |
| `SIGNING_KEY_PASSWORD` | key password |

Keep `whistlesong.jks` somewhere safe and out of the repo. If you lose it, you can't publish updates
that install over existing versions.

## Tips for best results

* Whistle 2–8 seconds, fairly close to the mic, in a quiet room.
* Separate repeated notes with a little breath or tongue articulation.
* The app picks a comfortable octave and tempo for each style, so a whistle at 90 BPM may come
  back as 180 BPM chiptune.
