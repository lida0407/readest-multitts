# Third-party components

## Speech engine

**sherpa-onnx** — Apache License 2.0
https://github.com/k2-fsa/sherpa-onnx
Bundled as `app/libs/sherpa-onnx.aar`, providing on-device neural speech synthesis.

## Bundled voices

**Piper en_US-ryan (medium, int8)** — MIT License
https://github.com/rhasspy/piper
English narration voice.

**icefall Matcha-TTS, Baker corpus** — Apache License 2.0
https://github.com/k2-fsa/icefall
Chinese narration voice, paired with **HiFi-GAN v2** (Apache License 2.0) as vocoder.

Both voice models are redistributed under their own licences, unmodified.

## Not bundled

Voices belonging to Microsoft, Nuance Vocalizer, IssTTS, Google and similar
proprietary engines are **not** included and must not be added: they are
licensed for use within their own products, not for redistribution inside
another application. Readest++ can use them at runtime through MultiTTS if the
reader has installed that separately, which is a different thing entirely.
