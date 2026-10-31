# RA Player

A gesture-driven video player for Android, written in Kotlin on top of Media3 ExoPlayer.

## Features

- Library of every video on the device, with thumbnails, search and sorting
- Resume where you left off, with a progress bar on each video
- Swipe the left half of the screen for brightness, the right half for volume
- Swipe sideways to scrub, double-tap the left or right side to jump 10 seconds
- Hold anywhere to play at 2x, release to go back to normal speed
- Pinch to switch between fit and crop, or tap the aspect button (Fit, Stretch, Crop)
- Playback speed, audio track and subtitle track menus in the player controls
- Load external subtitles (.srt, .vtt, .ass, .ssa, .ttml)
- A-B repeat for any section of a video
- Picture in picture, and automatic PiP when you leave the app while playing
- Lock the controls so touches are ignored
- Open network streams (MP4, HLS, DASH) and open files from other apps
- Hardware decoding with software fallback

## Build the APK

### GitHub Actions (no installs)

Open the **Actions** tab and run **Build APK** (it also runs on every push). When it finishes, download `RAPlayer-apk` from the run page and unzip it.

### Android Studio

Open this folder, wait for the Gradle sync, then **Build > Build APK(s)**.

### Command line

With JDK 17, the Android SDK and Gradle 8.9 installed:

```
gradle assembleRelease
```

The release build is signed with the debug key so it installs right away. Create your own keystore before publishing to Google Play.
