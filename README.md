# Akoway for Android

Free, offline voice typing for every app on your phone. Talk, and Akoway types it: it runs a speech
model on the phone itself, so nothing you say leaves it, and it tidies out "um"s, false starts and
repeated words as it goes.

Akoway for Windows lives at [codingSalafi/akoway](https://github.com/codingSalafi/akoway).

## Install

1. Download `Akoway-Android.apk` from the [latest release](https://github.com/codingSalafi/akoway-android/releases/latest)
   on your phone and open it. Android will ask you to allow installs from your browser; allow it.
2. Open Akoway and follow the three steps: download the voice model (243 MB, once), allow the
   microphone, and turn on the Akoway keyboard.

Needs Android 8 or newer on a 64-bit (arm64) phone, with about 1.5 GB of free memory while dictating.

## Using it

- **Akoway keyboard (main way):** in any text box, switch to "Akoway voice" with the keyboard button
  at the bottom of the screen or by long-pressing your keyboard's space bar. It starts listening
  straight away; tap the mic when you're done and your words are typed. The keyboard key takes you
  back to your usual keyboard.
- **Floating tab (optional):** a tab on the right edge of the screen that types into any app without
  switching keyboard. It uses Android's Accessibility permission. If Akoway is greyed out in the
  Accessibility list, open Settings › Apps › Akoway › ⋮ › Allow restricted settings, then try again.

## The speech model

Akoway uses its own conversion of [Underdog Bark 3.0 Clean](https://huggingface.co/ConwayResearch/Underdog-Bark-3.0-Clean)
(Conway Research, CC-BY-4.0), not the original files. Underdog publishes Bark for Python/PyTorch and Apple devices;
Akoway re-exports it to ONNX so it runs inside an Android app with ONNX Runtime, on the phone's CPU.

The app downloads the files once from [codingSalafi/akoway-bark-3.0-clean-onnx](https://huggingface.co/codingSalafi/akoway-bark-3.0-clean-onnx)
(the `fast/` folder, 243 MB, Brotli-compressed and checked with SHA-256). The `fast/` set is tuned for phones: the
encoder works in 8 bits end to end, about 3× faster than the desktop files (on a Pixel 6 Pro, about 1.2 s of work
for 10 s of speech), with transcripts that differ from the desktop version only in an occasional comma or capital.
The conversion scripts live in the [Windows repo](https://github.com/codingSalafi/Akoway/tree/main/tools/bark-onnx).

## Privacy

Speech recognition runs entirely on the phone. The only network access is the one-time model
download from Hugging Face. Akoway has no account, no analytics and no ads.

## Building

Kotlin, plain Android Views, ONNX Runtime. `build.cmd assembleDebug` (Windows, JDK from Android
Studio). Release builds are signed with a key kept outside this repository.

## Licence

MIT, see `LICENSE`. The speech model is CC-BY-4.0 by Conway Research (see the model's
`LICENSE-NOTICE.txt`). Instrument Serif is under the SIL Open Font License (`FONT-LICENSE-OFL.txt`).
