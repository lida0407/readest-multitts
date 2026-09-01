# Third-party components

## Speech engine

**sherpa-onnx** — Apache License 2.0
https://github.com/k2-fsa/sherpa-onnx
Bundled as `app/libs/sherpa-onnx.aar`, providing on-device neural speech synthesis.

## Bundled voices

**Piper en_US-ryan (medium, int8)** — MIT License
https://github.com/rhasspy/piper
English narration voice.

**Piper zh_CN-xiao_ya (medium, int8)** — MIT License
https://github.com/rhasspy/piper
Chinese narration voice.

**espeak-ng** data — GPL-3.0
https://github.com/espeak-ng/espeak-ng
Pronunciation data both voices phonemise through, shipped pruned to English and
Mandarin. Unmodified other than the removal of unused languages' dictionaries.

The voice models are redistributed under their own licences, unmodified.

## Not bundled

Voices belonging to Microsoft, Nuance Vocalizer, IssTTS, Google and similar
proprietary engines are **not** included and must not be added: they are
licensed for use within their own products, not for redistribution inside
another application. Readest++ can use them at runtime through MultiTTS if the
reader has installed that separately, which is a different thing entirely.
