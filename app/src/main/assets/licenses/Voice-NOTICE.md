# Offline speech recognition

The Android fallback uses [sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx) 1.13.8 (Apache 2.0) and the SenseVoice INT8 model converted by csukuangfj from [FunAudioLLM SenseVoiceSmall](https://huggingface.co/FunAudioLLM/SenseVoiceSmall). SenseVoice model weights are governed by the **FunASR Model Open Source License Agreement**, not the SDK Apache license. Source and authors: FunAudioLLM / Alibaba Group. Keep the SenseVoice model name and attribution when redistributing weights.

Public model revision: `2365baeacb507f821a0c8120fcee3d484dba7a07`. Model size 239,233,841 bytes, SHA-256 `c71f0ce00bec95b07744e116345e33d8cbbe08cef896382cf907bf4b51a2cd51`. Tokens SHA-256 `f449eb28dc567533d7fa59be34e2abca8784f771850c78a47fb731a31429a1dc`. SDK asset SHA-256 `633c24321e06b1fe79feafa03ea16cbc0f8a286641e2da3559bac91bdb13bd96`.

The SDK is fetched as a pinned dependency during the build. Weights are downloaded on explicit request (~240 MB; Wi-Fi recommended), excluded from backups and not bundled in the APK or Git repository. Audio is held in memory only, never persisted or uploaded, and decoded on the phone. Permissions and lifecycle cancellation apply to both system dictation and local inference. Text enters an editable draft and is never sent automatically. No zero-error recognition claim is made.

Older uncommitted builds used Vosk; the microphone worked on the test vivo phone, but Chinese transcription quality did not meet the user requirement. The final path uses SenseVoice.
