# Air piano: libraries and architecture

Oct 22, 2026 · Advisory research for a hobby Android app that plays piano notes from hand movements seen by the phone camera.

## Bottom line

- **Rust is a good fit for the audio engine and the gesture logic. It is a poor fit for hand tracking and camera capture on Android.** There is no maintained Rust binding for MediaPipe on Android, so doing tracking in Rust means re-implementing MediaPipe's two-stage pipeline yourself.
- **Recommended architecture:** a thin Kotlin shell (CameraX + MediaPipe Hand Landmarker + UI) that sends 21 landmarks per hand per frame over JNI to a Rust core (filtering, press detection, synth, AAudio output).
- **OCaml is feasible only with significant friction** (OCaml 4.x only via the DkML cross-compiler, no Android audio or ML bindings). I would not use it here unless learning OCaml is itself the goal; if so, confine it to the gesture logic.
- **Expect high latency.** A realistic camera-to-sound budget is roughly 100–170 ms, against a perceptual target of about 10–20 ms for instruments. This is a physics/hardware limit of camera-based input, not a language choice. Plan for it (see Latency).
- **The hardest problem is detecting a key press with one camera**, not the language. Orient the phone so the press motion lies in the image plane rather than along the camera axis.

## Hand tracking

### MediaPipe Hand Landmarker (recommended)

- Official Tasks API for Android (Kotlin/Java), Python, Web and iOS. Running mode `LIVE_STREAM` delivers results asynchronously through a callback.
- Two models: palm detection (192×192) then hand landmarks (224×224), float16. In live-stream mode the landmark model's box is reused across frames and palm detection only re-runs when tracking is lost, which keeps cost low.
- Google's own benchmark for the full pipeline on a Pixel 6: **17.1 ms CPU, 12.3 ms GPU** per frame. No numbers for other devices; mid-range phones will be slower.
- Output per hand: handedness, 21 landmarks in normalized image coordinates, and 21 "world" landmarks. `num_hands` defaults to 1, so set it to 2.
- Caveat: the z coordinate is inferred from a single image. It is relative (wrist-origin, scale-normalized) and noisy, not a true depth measurement. An independent validation against a stereo infrared system found MediaPipe usable for joint angles but noisier, with worse agreement for index and little fingers due to occlusion (Suwabe et al., 2024).

### Calling it from Rust

- **No native Rust binding.** `mediapipe-rs` (WasmEdge) targets WebAssembly/WASI-NN only, not Android.
- **DIY route:** extract the two `.tflite` models from the `.task` bundle and run them yourself, either through a TFLite/LiteRT C API binding or by converting to ONNX and using the `ort` crate (ONNX Runtime; Android is listed as a supported platform, but you will likely need to supply the Android `libonnxruntime.so` yourself). You would then re-implement anchor decoding, non-max suppression, rotated ROI cropping, landmark un-projection and the tracking loop. Realistically weeks of work, and easy to get subtly wrong.
- **Accelerator note:** Android's NNAPI is deprecated as of Android 15. Google points to TFLite (now LiteRT) with the GPU delegate instead, which is another reason to let MediaPipe handle acceleration.
- Verdict: keep tracking in Kotlin via MediaPipe. Only revisit the DIY route if you want tracking in Rust as a learning project.

## Audio

- Android guidance is to use **AAudio** (or Oboe, Google's C++ wrapper over AAudio/OpenSL ES). OpenSL ES was deprecated in 2020.
- **Rust options:**
  - `cpal` 0.18.2 (Aug 2026): AAudio is the default Android backend (API 26+), with an optional `realtime` feature that raises the callback thread priority. Actively maintained. Recommended default.
  - `oboe` crate 0.6.1: binds Oboe C++, exposes performance-mode and sharing-mode options directly, but the last release is March 2024, so treat it as lightly maintained.
- Measured device latency: average **round-trip** latency on the 20 most popular Android phones fell to 39 ms by Jan 2021 (range 28–56 ms); Android's pro-audio requirement is 20 ms round-trip (Google Android Developers Blog, 2021). Output-only latency is lower than round-trip; I have no primary figure for it.
- Synth: a sampler (one recorded piano note per few keys, pitch-shifted) or a SoundFont synth in Rust is enough. Keep the audio callback allocation-free and pass note events from the gesture thread through a lock-free single-producer/single-consumer ring buffer.
- Avoid Bluetooth output for playing; wireless audio adds substantial latency (I have not verified a specific figure here).

## Rust and OCaml on Android

### Rust (mature enough)

- Build: `cargo-ndk` cross-compiles to `arm64-v8a` etc. and drops `.so` files into `jniLibs`.
- Interop: the `jni` crate for hand-written bindings, or Mozilla's `uniffi` to generate Kotlin bindings. Data crossing the boundary is tiny (≈2 hands × 21 × 3 floats per frame), so JNI overhead is negligible.
- Pure-Rust Android apps (no Kotlin) are possible via `android-activity`, but then camera capture goes through the NDK Camera2 C API and tracking goes the DIY route above. Much more work for little gain.
- Big advantage: the gesture logic and synth are plain Rust, so you can develop and unit-test them on a desktop with recorded landmark data.

### OCaml (possible, costly)

- The DkML Android cross-compiler (announced Oct 2024) supports `arm64-v8a`, `armeabi-v7a` and `x86_64`, but **cannot use OCaml 5+**, and packages live in a custom opam repository. The broader DkML Windows distribution is being sunset (end of life 31 Dec 2026); the Android compiler packages are explicitly not sunset.
- `opam-cross-android` exists but is older and less maintained.
- No OCaml bindings for AAudio, MediaPipe or JNI-friendly tooling comparable to `jni`/`uniffi`; you would write C stubs and a JNI layer by hand.
- Verdict: technically feasible for a pure-logic core, but every integration point costs you hand-written C. Rust gets you the same "not Kotlin" benefit with far less friction.

## Latency budget

### What musicians perceive

- Jack et al. (2018, *Music Perception*): percussionists and amateurs rated 10 ms as no different from 0 ms, but rated **20 ms and 10 ms ± 3 ms jitter as worse**. Jitter matters as much as the mean.
- Wessel & Wright (2002, *Computer Music Journal*) proposed a target of about 10 ms latency with about 1 ms jitter for intimate musical control.
- Caveat: these studies used instruments with physical contact. An air instrument gives no tactile feedback, so users may tolerate more, but I found no study measuring that directly. Treat this as an open question you can test yourself.

### Estimated budget (camera to sound)

| Stage | Estimate | Basis |
| --- | --- | --- |
| Camera exposure + sensor-to-app | \~40–60 ms | Measured \~57 ms on a Pixel 2 at 30 fps (inovex, 2024); 60 fps should cut the exposure share |
| Hand landmark inference | 12–17 ms (Pixel 6) | Google benchmark; slower on mid-range phones |
| Press detection (needs 2–3 frames of motion) | \~17–50 ms at 60 fps | Depends on your detector; my estimate |
| Audio output (buffer + DAC) | \~10–25 ms | Estimate from \~39 ms average round-trip |
| **Total** | **\~80–150 ms+** | An order of magnitude above the 10–20 ms target |

### Mitigations

- Run the camera at 60 fps if the device supports it, with a small preview resolution.
- Use GPU for MediaPipe; keep `num_hands` at the minimum you need.
- **Predict onsets:** extrapolate fingertip velocity and fire the note when the trajectory is projected to cross the "key plane" within the next frame, rather than waiting to observe the crossing. This trades latency for false triggers.
- Use AAudio low-latency performance mode with the smallest stable buffer; wired headphones or the built-in speaker.
- Keep timing consistent (low jitter) rather than chasing the lowest mean: process every frame on a fixed path, never allocate in the audio thread.

## Detecting a key press in the air

This is where the project succeeds or fails.

- **Depth ambiguity:** with one camera, motion toward or away from the lens shows up only as small changes in apparent size and in MediaPipe's inferred z, which is noisy. A downward "press" filmed from above is therefore hard to see.
- **Fix it with geometry:** prop the phone in front of the hands at a low angle (or to the side) so the press motion is **vertical in the image** and key position is **horizontal in the image**. Both then come from the reliable x/y coordinates.
- **Detect relative motion, not absolute position:** use the fingertip relative to its knuckle (MCP) or the finger flexion angle. This separates a finger press from the whole hand moving.
- **Filter with low lag:** the One Euro filter (Casiez, Roussel & Vogel, CHI 2012) is designed for exactly this: noisy pointing input where lag matters.
- **State machine per finger:** threshold on downward velocity plus displacement, hysteresis (separate press and release thresholds), and a short refractory period to suppress double triggers. Map onset velocity to note loudness.
- **Expect imperfect accuracy:** Liang et al. (2016, I3D) built a virtual piano with a **depth (RGB-D) camera** and SVM tap classification and reached 85–100% precision and 87–99% recall per finger, workable only for slow rhythms. A monocular phone camera will do worse, especially for ring and little fingers (occlusion).
- **Simplify the instrument if needed:** e.g., one note per fingertip with hand position selecting the octave, or a large-key layout. Fewer, larger targets are much more robust than a full 88-key mapping.

## Recommended architecture

```
Kotlin shell (thin)
  CameraX (60 fps, small resolution)
    -> MediaPipe HandLandmarker (LIVE_STREAM, GPU, num_hands=2)
    -> callback: landmarks + timestamp
    -> JNI call into Rust (≈126 floats per frame)
  UI: overlay of landmarks and virtual keys, settings

Rust core (libairpiano.so, built with cargo-ndk)
  1. One Euro filtering per landmark
  2. Key mapping (x position -> key) and per-finger press state machine
  3. Note events -> lock-free SPSC ring buffer
  4. Audio thread (cpal on AAudio): sampler synth, no allocation
```

### Suggested order of work

1. **Prototype the gesture detector on a laptop** with MediaPipe in Python and a webcam. It is the fastest way to find out whether presses are detectable at all. Log landmark streams to files.
2. **Write the Rust core on the desktop**, replaying those logs in unit tests, with `cpal` playing sound locally.
3. **Minimal Android shell**: CameraX + MediaPipe with a landmark overlay. Measure fps and per-stage latency on your actual phone.
4. **Wire the Rust core in via `cargo-ndk` + `jni` (or `uniffi`).** Test audio latency first with an on-screen tap button.
5. Tune thresholds, add onset prediction, then measure end-to-end latency (film the hand and listen with a second phone recording at high frame rate).

## Open risks

- End-to-end latency may make it feel like a toy rather than an instrument. Worth measuring in step 1 before investing in polish.
- Press detection accuracy with one camera is unproven for fast playing.
- Performance on your specific phone is unknown; Google's numbers are for a Pixel 6.

## Sources

- Google, [Hand landmarks detection guide](https://developers.google.com/edge/mediapipe/solutions/vision/hand_landmarker) (MediaPipe Tasks).
- WasmEdge, [mediapipe-rs](https://github.com/WasmEdge/mediapipe-rs).
- Android Developers, [NNAPI migration guide](https://developer.android.com/ndk/guides/neuralnetworks/migration-guide).
- ONNX Runtime, [Build for Android](https://onnxruntime.ai/docs/build/android.html); [`ort` crate docs](https://docs.rs/ort/).
- [`cpal` 0.18.2 on docs.rs](https://docs.rs/crate/cpal/latest); [`oboe` crate on lib.rs](https://lib.rs/crates/oboe); [cargo-ndk](https://github.com/rib/cargo-ndk).
- D. Turner, [An update on Android's audio latency](https://android-developers.googleblog.com/2021/03/an-update-on-androids-audio-latency.html), Android Developers Blog, 2021.
- S. Lengfeld, [Glass-to-glass latency in Android](https://www.inovex.de/de/?p=49907), inovex, 2024.
- [\[ANN\] Simplified Android cross-compiler with DkML](https://discuss.ocaml.org/t/ann-simplified-android-cross-compiler-with-dkml/15407), OCaml Discuss, 2024; [\[ANN\] Sunsetting of DkML distribution](https://discuss.ocaml.org/t/ann-sunsetting-of-dkml-distribution/17530), 2025; [opam-cross-android](https://github.com/ocaml-cross/opam-cross-android).
- R. H. Jack, A. Mehrabi, T. Stockman, A. McPherson, [Action-sound latency and the perceived quality of digital musical instruments](https://qmro.qmul.ac.uk/xmlui/bitstream/handle/123456789/44614/Jack%20Action-sound%20Latency%202018%20Accepted.pdf?sequence=1), *Music Perception*, 2018.
- D. Wessel, M. Wright, [Problems and prospects for intimate musical control of computers](https://opensoundcontrol.stanford.edu/publications/2002-Problems-and-Prospects-for-Intimate-Musical-Control-of-Computers.html), *Computer Music Journal*, 2002.
- H. Liang et al., [Barehanded Music: Real-time Hand Interaction for Virtual Piano](https://cse.buffalo.edu/~jsyuan/papers/2016/Barehanded%20Music,%20Real-time%20Hand%20Interaction%20for%20Virtual%20Piano.pdf), ACM I3D, 2016.
- R. Suwabe, T. Saito, T. Hamaguchi, [Verification of criterion-related validity for developing a markerless hand tracking device](https://www.mdpi.com/2313-7673/9/7/400), *Biomimetics* 9(7):400, 2024.
- G. Casiez, N. Roussel, D. Vogel, "1€ Filter: a simple speed-based low-pass filter for noisy input in interactive systems", CHI 2012.
