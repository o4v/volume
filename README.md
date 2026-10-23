# CleanBoost

A system-wide volume booster for Android 9+ that pins output peaks under a ceiling, so it doesn't clip.

## Build
Open the `CleanBoost` folder in Android Studio (Ladybug or newer), let Gradle sync, then Run.
Studio generates the Gradle wrapper jar on first sync if it's missing.

## First launch
1. **Allow microphone**: Android puts its output-level meter (the Visualizer API) behind this
   permission. Nothing is recorded. Clip Guard uses it to measure real output peaks.
2. **Allow notifications**: needed for the foreground service.
3. **Tap "Allow background running"** so Doze and OEM task killers leave the engine alone.
   On Xiaomi, Huawei, OnePlus and Samsung, also set the app to "No restrictions" or
   "Unrestricted" under Battery, and lock it in Recents.

## Signal chain
```
boost (input gain) → parametric EQ (48-band) → 4-band compressor → linked limiter
                                                 ├ density: slow attack, transients pass through
                                                 ├ exciter: upward lift of quiet highs
                                                 └ warmth: upward lift of quiet lows
```
All of this runs in float inside Android's DynamicsProcessing effect.

## Why other boosters clip, and how this one avoids it
In AOSP (`DPFrequency.cpp`), the DynamicsProcessing limiter works on **blocks**. It measures
each 10–20 ms block's RMS energy and has no look-ahead. A limiter set to −1 dB holds the
*block RMS* at −1 dB. The actual sample peaks inside that block sit higher by the music's crest
factor (6–18 dB), so pushing the boost sends them past 0 dBFS and they clip.

**Clip Guard** places the limiter threshold at `ceiling − crest margin`. It learns the margin
live from the output's measured peak-to-RMS ratio (with 60 s of memory), raises it immediately
whenever a peak comes near the ceiling, and lowers it slowly (0.5 dB/s).
Without the meter, it falls back to a fixed 18 dB margin.

Simulation of the AOSP limiter algorithm on test music (drums at 0 dBFS):

| boost | naive limiter (−1 dB) | Clip Guard |
|---|---|---|
| +12 dB | peaks +12 dBFS, 1.4 % samples clipped | peaks < −1 dBFS, 0 clipped |
| +20 dB | peaks +14 dBFS, 4.9 % clipped | peaks < −1 dBFS, 0 clipped |
| +30 dB | peaks +14 dBFS, 6.8 % clipped | peaks < −1 dBFS, 0 clipped |

## The physics, honestly
- Digital audio can't go above 0 dBFS. Once peaks reach the ceiling, more boost can only make
  the sound **denser** (higher average level), not taller. Clean loudness is available in
  proportion to the source's headroom. Quiet recordings and loudness-normalized streams
  (Spotify and YouTube turn many tracks down 5–10 dB) gain the most.
- "Louder without touching transients" and "louder at a fixed peak" pull against each other.
  The density compressor's slow attack keeps hits intact, and the limiter scales whole blocks
  rather than flattening waveforms. Past a certain boost, though, something has to give.
- **Headphone cap:** Android's "safe media volume" (EU EN 50332 / IEC 62368) and some OEM
  limiters (Samsung: *Settings → Sounds → Volume → Media volume limiter*) cap the headphone
  amp. No app can lift that cap without root. The setting is yours to turn off on your device.

## Limits
- Global mode (session 0) is deprecated by Google but still works on most phones. Some devices
  (offloaded/Dolby paths) skip global effects. Players that announce their audio session
  (most music apps) are attached individually as well.
- Clip Guard's reactive bump relies on the Visualizer seeing post-effect output, which is
  device-dependent. Crest tracking works either way.
- Protect your hearing: sustained levels above ~85 dB SPL cause permanent damage.
