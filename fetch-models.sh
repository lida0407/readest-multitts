#!/usr/bin/env bash
# Downloads the bundled TTS engine and voice models.
#
# These live outside git on purpose: together they are ~165MB of binary weights
# that would bloat every clone and every future checkout forever. Run this once
# after cloning, and again if the model list below changes.
set -euo pipefail
cd "$(dirname "$0")"

ASSETS="app/src/main/assets/tts"
LIBS="app/libs"
SHERPA_VERSION="1.13.7"
BASE="https://github.com/k2-fsa/sherpa-onnx/releases/download"

mkdir -p "$ASSETS" "$LIBS" .model-cache

fetch() { # url dest
  [ -f "$2" ] && { echo "have $(basename "$2")"; return; }
  echo "downloading $(basename "$2")…"
  curl -fL --retry 3 -o "$2" "$1"
}

# Engine: sherpa-onnx (Apache-2.0)
fetch "$BASE/v$SHERPA_VERSION/sherpa-onnx-static-link-onnxruntime-$SHERPA_VERSION.aar" "$LIBS/sherpa-onnx.aar"

# English voice: Piper en_US-ryan medium, int8 (MIT)
if [ ! -d "$ASSETS/vits-piper-en_US-ryan-medium" ]; then
  fetch "$BASE/tts-models/vits-piper-en_US-ryan-medium-int8.tar.bz2" .model-cache/en.tar.bz2
  tar xf .model-cache/en.tar.bz2 -C .model-cache
  mv .model-cache/vits-piper-en_US-ryan-medium-int8 "$ASSETS/vits-piper-en_US-ryan-medium"
  rm -f "$ASSETS/vits-piper-en_US-ryan-medium/MODEL_CARD"
fi

# Chinese voice: Matcha trained on Baker, 22.05kHz (Apache-2.0)
if [ ! -d "$ASSETS/matcha-icefall-zh-baker" ]; then
  fetch "$BASE/tts-models/matcha-icefall-zh-baker.tar.bz2" .model-cache/zh.tar.bz2
  tar xf .model-cache/zh.tar.bz2 -C "$ASSETS"
  rm -f "$ASSETS/matcha-icefall-zh-baker/README.md"
  # Matcha only produces spectrograms; a vocoder turns those into audio.
  fetch "$BASE/vocoder-models/hifigan_v2.onnx" "$ASSETS/matcha-icefall-zh-baker/hifigan_v2.onnx"
fi

echo
du -sh "$ASSETS"/* "$LIBS/sherpa-onnx.aar"
echo "Models ready."
