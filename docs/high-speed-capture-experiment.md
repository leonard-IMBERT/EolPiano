# High-speed camera capture experiment

Oct 22, 2026 · Experiment design for the air piano: getting more than 30 fps of camera frames into the app on a Samsung Galaxy S25 FE, by tapping the high-speed recording path instead of the normal camera stream.

## Summary

- With auto-exposure on, normal mode gives apps **at most 30 fps** on every camera. At 30 fps, the camera-related part of the latency budget is roughly 100 ms before hand tracking even runs. The streams themselves allow 60 fps at 1080p and below, which manual exposure might unlock (untested, see [What the phone reports](#what-the-phone-reports)).
- The phone supports Android's **constrained high-speed mode** (the slow-motion path) at **120 fps on the rear main camera and both front cameras** (240 fps on the rear main camera), with 1280×720 as the smallest size. In that mode only the **recording** output is guaranteed the full frame rate; the preview output is only guaranteed 30 fps.
- Proposal: route the recording output into a **hardware video encoder**, take the compressed frames from memory (no file, no ramfs), decode them immediately with a **low-latency hardware decoder**, and feed the decoded images to MediaPipe.
- This is an experiment. Encode and decode delay, frame batching and inference speed may eat most of the gain. The goal of this document is a small test app that measures the real gain before anything else is built.

## Background

### What the phone reports

Measured on Oct 25, 2026 with the app's `CameraProbe` (Camera2 `CameraCharacteristics`) on the user's device (SM-S731B, Android SDK 37). These are values the camera HAL reports, not measured frame rates.

| Camera ID | Facing | Focal length | Hardware level | High-speed mode | Sensor orientation |
| --- | --- | --- | --- | --- | --- |
| 0 | Back (logical, main) | 5.4 mm | FULL | Yes | 90° |
| 1 | Front | 3.22 mm | LIMITED | Yes | 270° |
| 2 | Back (ultra-wide, judging by focal length) | 1.74 mm | LIMITED | No | 90° |
| 3 | Front | 3.22 mm | LIMITED | Yes | 270° |

Common to all four cameras:

- Timestamp source: **`REALTIME`**. Sensor timestamps share a clock with `SystemClock.elapsedRealtimeNanos()`, so sensor-to-app latency can be computed directly.
- Capabilities include `MANUAL_SENSOR`, `READ_SENSOR_SETTINGS` and `BURST_CAPTURE`. Cameras 0, 1 and 3 also list `CONSTRAINED_HIGH_SPEED_VIDEO`; camera 0 also lists `MANUAL_POST_PROCESSING`, `RAW` and `LOGICAL_MULTI_CAMERA`.
- AE target fps ranges: `[15, 15]`, `[15, 20]`, `[20, 20]`, `[24, 24]`, `[15, 30]`, `[30, 30]`. With auto-exposure on, normal mode tops out at 30 fps.
- ISO 25–3200. Exposure time 85 µs–100 ms (back cameras), 92 µs–80 ms (front cameras), so exposures ≤ 8.3 ms for 120 fps are possible given enough light.

#### High-speed (constrained) video

| Camera ID | Sizes | FPS ranges |
| --- | --- | --- |
| 0 | 1280×720, 1920×1080, 3840×2160 | `[30, 120]`, `[120, 120]`, `[30, 240]`, `[240, 240]` |
| 1, 3 | 1280×720, 1920×1080 | `[30, 120]`, `[120, 120]` |
| 2 | none | none |

- The front cameras do reach 120 fps; spec sheets (which describe the stock camera app) only list 60 fps for the selfie camera.
- The smallest high-speed size is **1280×720**, so the encoder and decoder work at 720p. Nothing smaller is offered.
- Use the fixed range `[120, 120]`: a fixed range is required when both preview and recording surfaces are in the session. 240 fps on camera 0 is possible but quadruples the per-second work of every later stage; start at 120.

#### Normal-mode maximum frame rate per output size

From `getOutputMinFrameDuration(format, size)` (16 665 880 ns ≈ 60 fps, 33 331 760 ns ≈ 30 fps):

| Camera ID | `YUV_420_888` (`ImageReader`, CPU access) | `PRIVATE` (GPU, encoder, preview surfaces) |
| --- | --- | --- |
| 0 | 30 fps at every size | 60 fps at 1920×1440 and every listed size below it, except 2336×1080 (30 fps); 30 fps at 2560×1440 and above |
| 1, 2, 3 | Same as `PRIVATE` on camera 0 | Same as `PRIVATE` on camera 0 |

The AE ranges stop at 30 fps, but the streams allow 60 fps, and every camera has `MANUAL_SENSOR`. A normal session with auto-exposure off (`CONTROL_AE_MODE_OFF`, `SENSOR_FRAME_DURATION` = 16 666 666 ns, exposure and ISO set by the app) might therefore deliver 60 fps without any encoder or decoder. On camera 0 this only applies to `PRIVATE` outputs, not to a YUV `ImageReader`. **Untested**: worth a quick check before or alongside the high-speed path, since it is much simpler.

### What the video codecs report

Measured on Oct 25, 2026 with the app's `CodecProbe` (`MediaCodecList(REGULAR_CODECS)`, aliases skipped), for 1280×720, the size the high-speed path records at. As with the camera, these are vendor declarations, not measured throughput.

| Codec | Type | 720p@120 / @240 supported | Achievable fps at 720p | Performance points covering 720p@120 / @240 | Other |
| --- | --- | --- | --- | --- | --- |
| `c2.exynos.h264.encoder` | HW encoder | yes / yes | **145–146** | 3840×2160@120, 1920×1088@240 / 1920×1088@240 | `COLOR_FormatSurface`; Baseline and Constrained Baseline profiles; CBR, VBR (no CQ) |
| `c2.exynos.h264.decoder` | HW decoder | yes / yes | **514–520** | same | **`FEATURE_LowLatency`** |
| `c2.exynos.hevc.encoder` | HW encoder | yes / yes | 145–147 | same | `COLOR_FormatSurface`; CBR, VBR (no CQ) |
| `c2.exynos.hevc.decoder` | HW decoder | yes / yes | 512–519 | same | `FEATURE_LowLatency` |
| `c2.android.avc.encoder`, `c2.android.hevc.encoder` | SW encoders | no / no | (91–200 for AVC) | not published | Unusable for 720p@120 |
| `c2.android.avc.decoder`, `c2.android.hevc.decoder` | SW decoders | yes / yes (HEVC: no at 240) | 112–246, 157–346 | not published | No `FEATURE_LowLatency` |

Conclusions:

- **The encoder/decoder path is feasible.** The Exynos H.264 encoder accepts a camera input surface (`COLOR_FormatSurface`) at 720p@120, offers Baseline profile (no B-frames) and CBR/VBR; the Exynos H.264 decoder declares `FEATURE_LowLatency`, so `KEY_LOW_LATENCY` should be honoured rather than ignored.
- **Use `c2.exynos.h264.encoder` and `c2.exynos.h264.decoder`, created by name** (`MediaCodec.createByCodecName`). `createEncoderByType` lets the system choose, and the software encoder cannot do 720p@120.
- **The encoder is the tight stage.** Its measured "achievable" rate at 720p is about 146 fps, so 120 fps leaves little headroom. The performance points claim 1080p@240, which contradicts that figure; treat 240 fps as doubtful until measured.
- **The decoder has ample headroom** (~515 fps at 720p), so it should not limit 120 fps.
- HEVC reports the same speeds as H.264 and brings nothing here; keep H.264, the safer choice.

### Why the recording path, not the preview

Android's documentation for constrained high-speed sessions states: "the preview output FPS is at least 30fps, the recording output FPS is maximum FPS of the requested FPS range". It also states that high-speed requests "must be sent in batched groups, with all requests sharing the same settings", and that when both preview and recording surfaces are used the FPS range must be fixed (min == max, e.g. \[120, 120\]).

Consequences:

- Reading the preview through an OpenGL texture (the workaround first suggested) may only deliver 30 fps. It is device-dependent and must not be relied on.
- The recording surface is the only output guaranteed the high frame rate. On Android a recording surface is the input surface of a `MediaCodec` encoder or a `MediaRecorder`.
- Frames are likely delivered in bursts (for example several frames per 33 ms tick at 120 fps) rather than evenly spaced. This adds latency and jitter that must be measured.

### Why not a file or ramfs

- Apps cannot mount or write to a ramfs without root, and MP4 writing via `MediaMuxer` / `MediaRecorder` needs a seekable file.
- `MediaCodec` already hands encoded frames to the app in memory through a callback, so no file is needed at all.

## Pipeline design

```
Camera (high-speed session, e.g. 120 fps, smallest supported size)
  ├─ preview surface  -> SurfaceView (on-screen, ≥30 fps; required? see notes)
  └─ recording surface = encoder input surface
        MediaCodec H.264/HEVC encoder (low latency, no B-frames, short GOP)
          -> onOutputBufferAvailable: encoded frame in memory
        MediaCodec decoder (KEY_LOW_LATENCY if supported)
          -> output surface = ImageReader (YUV_420_888)
        ImageReader.onImageAvailable
          -> MediaPipe HandLandmarker (LIVE_STREAM, GPU)
          -> landmarks -> JNI -> Rust core
```

All stages run in Kotlin for this experiment. As far as I know, the NDK camera API does not expose constrained high-speed sessions, so this part stays on the Java/Kotlin side even in the final app. The Rust core is unaffected because it only receives landmarks.

## Implementation notes

These are from my knowledge of the Android APIs and should be checked against the current reference docs while building.

### 1. Query high-speed capabilities

- `CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP` → `getHighSpeedVideoSizes()`, then `getHighSpeedVideoFpsRangesFor(size)` for each size.
- Do this for camera IDs 0, 1 and 3. Pick the **smallest** size that supports ≥120 fps. MediaPipe only needs 224×224 crops, and smaller frames encode and decode faster.
- Also read `SENSOR_INFO_TIMESTAMP_SOURCE`. If it is `REALTIME`, sensor timestamps share a clock with `SystemClock.elapsedRealtimeNanos()`, which makes latency measurement straightforward.
- **Done** (Oct 25, 2026): the smallest size is 1280×720 at `[120, 120]` on cameras 0, 1 and 3, and the timestamp source is `REALTIME` on every camera. See [What the phone reports](#what-the-phone-reports).

### 2. Configure the encoder

- `MediaCodec.createEncoderByType("video/avc")` (H.264 is the safest choice; try HEVC later). On the S25 FE, use `MediaCodec.createByCodecName("c2.exynos.h264.encoder")` instead, so the software encoder is never picked (see [What the video codecs report](#what-the-video-codecs-report)).
- `MediaFormat` keys to set:
  - `KEY_FRAME_RATE` = 120 (or the chosen rate), `KEY_COLOR_FORMAT` = `COLOR_FormatSurface`.
  - High `KEY_BIT_RATE` (quality matters more than size; nothing is stored).
  - `KEY_I_FRAME_INTERVAL` small (e.g. 1 s); also try all-intra if supported.
  - `KEY_MAX_B_FRAMES` = 0 (B-frames force reordering delay).
  - `KEY_LATENCY` = 1 (asks the encoder to output each frame as soon as possible; support varies).
- Call `createInputSurface()` and use that surface as the camera's recording output.
- Use the asynchronous callback mode; in `onOutputBufferAvailable`, pass the buffer and its `presentationTimeUs` straight to the decoder.

### 3. Create the high-speed session

- Use `createCaptureSession(SessionConfiguration)` with `SessionConfiguration.SESSION_HIGH_SPEED` (the older `createConstrainedHighSpeedCaptureSession` is deprecated) with the encoder input surface, plus a preview surface if the device requires one.
- Build one `CaptureRequest` targeting the surfaces with `CONTROL_AE_TARGET_FPS_RANGE` = \[120, 120\], expand it with `createHighSpeedRequestList()`, and submit with `setRepeatingBurst()`.
- Lock exposure time at or below the frame period (≤8.3 ms at 120 fps); good lighting helps.

### 4. Configure the decoder

- `MediaCodec.createDecoderByType("video/avc")` (on the S25 FE: `createByCodecName("c2.exynos.h264.decoder")`), configured with the encoder's output format (from `onOutputFormatChanged`, which carries the SPS/PPS).
- If `codecInfo.getCapabilitiesForType(...).isFeatureSupported(FEATURE_LowLatency)`, set `KEY_LOW_LATENCY` = 1.
- Output surface: `ImageReader.newInstance(w, h, YUV_420_888, maxImages)`. Render each output buffer immediately (`releaseOutputBuffer(index, true)`).

### 5. Feed MediaPipe

- In `onImageAvailable`, wrap the `Image` as an `MPImage` (`MediaImageBuilder`) and call `detectAsync` with the frame's original timestamp.
- Close the image promptly; drop frames rather than queue them if inference falls behind (queueing adds latency).

## What to measure

Record per frame, using the sensor timestamp as frame identity:

| Stage | Timestamp source |
| --- | --- |
| Exposure start | `SENSOR_TIMESTAMP` from the capture result |
| Encoder output | `elapsedRealtimeNanos()` in `onOutputBufferAvailable` |
| Decoder output | `elapsedRealtimeNanos()` in `onImageAvailable` |
| Landmarks ready | `elapsedRealtimeNanos()` in the MediaPipe result callback |

Derived metrics:

- **Delivered fps** at each stage (does 120 fps survive to the app?).
- **Sensor-to-app latency**: median and 95th percentile.
- **Batching pattern**: inter-arrival times at the decoder output (evenly spaced vs bursts).
- **Inference time** per frame and the fraction of frames dropped.

### Baseline

Run the same measurements with a normal CameraX `ImageAnalysis` stream at 30 fps on the same camera and lighting. The comparison is what matters, not the absolute numbers.

### Decision rule

The high-speed path is worth keeping if both hold:

1. Landmarks arrive at ≥ 90 fps sustained.
2. Sensor-to-landmarks latency (median) is at least \~20 ms lower than the 30 fps baseline, and p95 is not worse.

If landmarks arrive fast but per-frame latency is higher than baseline, the path can still win overall because press detection needs 2–3 frames; estimate end-to-end as `sensor-to-landmarks + 2 × frame period` for both and compare.

## Expected outcome (rough estimate)

| Stage | 30 fps normal | 120 fps high-speed path |
| --- | --- | --- |
| Exposure (up to one frame) | 0–33 ms | 0–8 ms |
| Sensor to app | \~24 ms (Pixel 2 measurement) | unknown; encoder + decoder add an estimated 1–3 frames |
| Press detection (2–3 frames) | 67–100 ms | 17–25 ms |
| Hand tracking | 12–17 ms on Pixel 6 GPU | same per frame, but must keep up with 120 fps |

The best case saves roughly 50–80 ms. Batching, codec buffering and dropped frames could cut that substantially. Only measurement will tell.

## Risks and open questions

- ~~The front camera's high-speed sizes and rates are unknown; they may not include 120 fps.~~ Resolved: both front cameras offer 120 fps at 720p and 1080p.
- Some devices require a preview surface in high-speed sessions; adding one should not change the recording rate, but check.
- Encoder `KEY_LATENCY` and decoder `KEY_LOW_LATENCY` are hints; Samsung's codecs may ignore them. The Exynos decoders do declare `FEATURE_LowLatency`, which makes the decoder hint likely to work; the encoder hint remains unverified.
- The hardware encoder's declared achievable rate at 720p (~146 fps) leaves little headroom above 120 fps; watch for dropped frames at the encoder output.
- Compression artefacts at small sizes could reduce landmark accuracy; compare tracking quality with the baseline.
- Running camera, encoder, decoder and GPU inference together at 120 fps may heat the phone and trigger throttling over a long session.
- Samsung could restrict the high-speed path to its own camera app in a future update.

## Sources

- Android Open Source Project, [`CameraConstrainedHighSpeedCaptureSession` javadoc](https://code.yawk.at/android/android-9.0.0_r35/android/hardware/camera2/CameraConstrainedHighSpeedCaptureSession.java) (quotes on preview vs recording fps, batching, fixed fps ranges).
- Android Developers reference: [`CameraConstrainedHighSpeedCaptureSession`](https://developer.android.com/reference/android/hardware/camera2/CameraConstrainedHighSpeedCaptureSession), [`StreamConfigurationMap`](https://developer.android.google.cn/reference/android/hardware/camera2/params/StreamConfigurationMap). The `MediaCodec` / `MediaFormat` keys above are from memory; check them against the current reference.
- Google, [MediaPipe Hand Landmarker guide](https://developers.google.com/edge/mediapipe/solutions/vision/hand_landmarker) (Pixel 6 timings).
- S. Lengfeld, [Glass-to-glass latency in Android](https://www.inovex.de/de/?p=49907), inovex, 2024 (sensor-to-app \~24 ms on Pixel 2).
- [GSMArena, Galaxy S25 FE specifications](https://m.gsmarena.com/samsung_galaxy_s25_fe_5g-14042.php) (stock camera video modes).
- Companion doc: [Air piano: libraries and architecture](libraries-and-architecture.md).
