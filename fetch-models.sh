#!/usr/bin/env bash
# Downloads the bundled TTS engine and voice models.
#
# These live outside git on purpose: they are binary weights that would bloat
# every clone forever. Run once after cloning, and again if the list changes.
set -euo pipefail
cd "$(dirname "$0")"

ASSETS="app/src/main/assets/tts"
LIBS="app/libs"
SHERPA_VERSION="1.13.7"
BASE="https://github.com/k2-fsa/sherpa-onnx/releases/download"
CACHE=".model-cache"

mkdir -p "$ASSETS" "$LIBS" "$CACHE"

fetch() { # url dest
  [ -f "$2" ] && { echo "have $(basename "$2")"; return; }
  echo "downloading $(basename "$2")..."
  curl -fL --retry 3 -o "$2" "$1"
}

# Engine: sherpa-onnx (Apache-2.0)
fetch "$BASE/v$SHERPA_VERSION/sherpa-onnx-static-link-onnxruntime-$SHERPA_VERSION.aar" "$LIBS/sherpa-onnx.aar"

# Both voices are Piper int8 at 22.05kHz (MIT). They phonemise through one
# shared espeak-ng data directory, which is pruned to the two languages we
# actually ship: the full set is 18MB, most of it dictionaries for languages
# this app has no voice for.
fetch "$BASE/tts-models/vits-piper-en_US-ryan-medium-int8.tar.bz2" "$CACHE/en.tar.bz2"
fetch "$BASE/tts-models/vits-piper-zh_CN-xiao_ya-medium-int8.tar.bz2" "$CACHE/zh.tar.bz2"

rm -rf "$CACHE/en" "$CACHE/zh"
mkdir -p "$CACHE/en" "$CACHE/zh"
tar xf "$CACHE/en.tar.bz2" -C "$CACHE/en" --strip-components=1
tar xf "$CACHE/zh.tar.bz2" -C "$CACHE/zh" --strip-components=1

rm -rf "$ASSETS"
mkdir -p "$ASSETS/en_US-ryan" "$ASSETS/zh_CN-xiao_ya" "$ASSETS/espeak-ng-data"

cp "$CACHE/en/en_US-ryan-medium.onnx" "$ASSETS/en_US-ryan/model.onnx"
cp "$CACHE/en/tokens.txt" "$ASSETS/en_US-ryan/"

cp "$CACHE/zh/zh_CN-xiao_ya-medium.onnx" "$ASSETS/zh_CN-xiao_ya/model.onnx"
cp "$CACHE/zh/tokens.txt" "$CACHE/zh/lexicon.txt" "$ASSETS/zh_CN-xiao_ya/"
for f in phone.fst date.fst number.fst; do cp "$CACHE/zh/$f" "$ASSETS/zh_CN-xiao_ya/"; done

ESPEAK="$CACHE/en/espeak-ng-data"
cp -R "$ESPEAK/lang" "$ESPEAK/voices" "$ASSETS/espeak-ng-data/"
for f in phondata phonindex phontab intonations phondata-manifest en_dict cmn_dict; do
  cp "$ESPEAK/$f" "$ASSETS/espeak-ng-data/"
done

echo
du -sh "$ASSETS"/* "$LIBS/sherpa-onnx.aar"
echo "Models ready."
