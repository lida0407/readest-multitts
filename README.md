# Readest++ (MultiTTS)

Android book reader with offline text-to-speech: read TXT / EPUB / MOBI / PDF, or
listen to them through the [MultiTTS](https://github.com/jing332/tts-server-android)
engine with narration pre-synthesized to local audio so playback works offline at
0% TTS CPU.

## Features

- **Reader** — paginated or scrolling, five themes, adjustable type, bookmarks,
  table of contents, and per-sentence karaoke highlighting.
- **Listening** — background narration with a real lock-screen player, chapter
  auto-advance with audible chapter cues, sleep timer, and a chapter scrubber.
- **Offline audio cache** — pre-synthesize a chapter or a whole book in a
  foreground service (survives the screen going off), resumable from a checkpoint,
  with per-book management and export to `.m4a` files in `Music/Readest++`.

## Build

```bash
export JAVA_HOME=/path/to/jdk-21
export ANDROID_HOME=/path/to/android-sdk
./gradlew assembleDebug
```

The APK lands in `app/build/outputs/apk/debug/`.

## Updating the phone

Every push to `main` builds an APK in GitHub Actions — download it from the run's
artifacts. Pushing a tag publishes it as a release:

```bash
git tag v1.13.0 && git push --tags
```

## Requirements

- Android 7.0+ (minSdk 24)
- [MultiTTS](https://github.com/jing332/tts-server-android) installed for narration.
  Offline voice packs (Sherpa, Microsoft offline) cache roughly 10× faster than
  network voices.

### Enabling the automatic build

The CI workflow lives at `.github/workflows/build.yml` but needs a token with the
`workflow` scope to upload. Once, run:

```bash
gh auth refresh -s workflow
git add .github && git commit -m "Add APK build workflow" && git push
```

After that every push builds an APK automatically.
