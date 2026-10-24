# Research notes

Background research for EolPiano, an Android app that plays piano notes from hand movements seen by the phone camera. These are design notes, not specifications: figures marked as estimates have not been measured on the target device.

| Document | What it covers |
| --- | --- |
| [Libraries and architecture](libraries-and-architecture.md) | Hand tracking (MediaPipe), audio (AAudio, `cpal`), Rust vs OCaml on Android, latency budget, press detection, recommended Kotlin shell + Rust core architecture. |
| [High-speed capture experiment](high-speed-capture-experiment.md) | Getting more than 30 fps into the app on a Galaxy S25 FE by tapping the constrained high-speed recording output through a MediaCodec encoder and decoder; the phone's measured Camera2 capabilities (high-speed sizes and rates, per-size max fps) and video codec capabilities; what to measure and when to keep it. |
| [Fingertip tracking with nail markers](fingertip-tracking-nail-markers.md) | Whether calibration or coloured / retroreflective nail markers can make fingertip tracking faster; press detection; hybrid marker + MediaPipe design and a first test. |

Target device: Samsung Galaxy S25 FE (SM-S731B), Android 17 (SDK 37).

Exported on 2026-10-24 from the original Claude Docs, which may have been edited since:
[libraries and architecture](https://claude.ai/artifact/552C2mQk2o457zGywic7Ck),
[high-speed capture](https://claude.ai/artifact/NGV6136774Xhc9bB5jRJkJ),
[nail markers](https://claude.ai/artifact/6wY4s3MLBGoa7FurLjWKnX).
