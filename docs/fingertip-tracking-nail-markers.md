# Calibration and nail markers: fast fingertip tracking

Oct 22, 2026 · @Léonard

## Verdict

Nail markers are the stronger idea and can plausibly bring fingertip localization down to about 1 ms per frame at 120 fps. Calibration helps press detection and key mapping much more than it helps inference speed, because MediaPipe already tracks a cropped region instead of detecting every frame.

- **Calibration / ROI:** useful, but the speed gain you imagine is mostly already built into MediaPipe. Its real value is per-finger press thresholds and the key layout.
- **Geometric patterns (codes, shapes) on nails:** will not work. A nail is roughly 15 to 25 px wide in the frame and motion blur at 120 fps is of the same order, so shapes are unreadable.
- **Solid colors (or retroreflective stickers) on nails:** works, cheap, and pure Rust is enough. The weak points are finger identity with 10 fingers, lighting, and camera angle (nails must face the lens).
- **Best bet:** markers tracked every frame, MediaPipe running slower in parallel to fix identity and reject false blobs.

All pixel and timing figures below are my estimates from geometry, not measurements on your phone. The last section proposes a one-evening test that checks them before you write any app code.

## Idea 1: calibrated fixed setup

MediaPipe already does ROI tracking: the palm detector runs only on the first frame or when tracking is lost, and later frames crop around the previous frame's landmarks ([Bazarevsky, Zhang et al. 2020](https://arxiv.org/abs/2006.10214); [MediaPipe docs](https://developers.google.com/edge/mediapipe/solutions/vision/hand_landmarker)). So a calibration round does not remove a cost that is currently paid every frame.

What remains is the landmark model itself, run once per hand:

| Landmark model | Samsung S20 (ms, per hand) | Pixel 3 (ms) |
| --- | --- | --- |
| Lite | 5.6 | 6.6 |
| Full | 11.1 | 16.1 |
| Heavy | 25.8 | 36.9 |

Source: Table 3 of the MediaPipe Hands paper (2020 hardware). Google's current docs give 17 ms CPU / 12 ms GPU for the whole Full pipeline on a Pixel 6. Your Exynos 2400 should be faster, but two hands with Lite is still around the 8 ms budget, not comfortably under it.

Where calibration genuinely helps:

- **Press thresholds per finger.** A recorded round gives each finger's rest height and pressed height in the image, which is the hard part of the whole app (see Press detection).
- **Key mapping.** A fixed phone mount turns image x into a key without per-session guessing.
- **Cropping the playing area.** Feeding only the region where hands can be shrinks the frame before anything else runs. This helps markers and the palm detector, not the landmark model, which always sees a 224 x 224 crop.
- **Skipping palm detection entirely.** Possible only by running the landmark TFLite model yourself (LiteRT C API from Rust) on crops you choose. The MediaPipe Tasks API does not expose this. It saves the rare re-detection, not the per-frame cost.

Weakness: a fixed setup breaks as soon as the phone or the player shifts, so calibration needs to be quick to redo (a few seconds of "rest, then press each finger").

## Idea 2: nail markers with classic CV

Color-coded markers are an established way to make hand tracking cheap ([Wang & Popović, SIGGRAPH 2009](https://people.csail.mit.edu/rywang/handtracking) used a colored glove for exactly this reason). Nail paint is a minimal version of that, and it suits Rust: no ML runtime, no OpenCV needed.

**Expected size in the image (estimate).** With a horizontal field of view near 70° at 40 cm, the frame spans about 56 cm. At 1280 px wide that is about 2.3 px/mm, so a 8 to 12 mm nail is roughly 18 to 28 px wide, and less when seen at an angle.

**Motion blur (estimate).** At 120 fps exposure is at most 8.3 ms. A fingertip moving at 1 m/s (an assumed order of magnitude for a fast strike) smears 4 mm, about 9 px, during a 4 ms exposure.

That is why geometric codes fail and colors work:

| Marker type | Readable at \~20 px with \~9 px blur? | Cost per frame | Main weakness |
| --- | --- | --- | --- |
| ArUco / AprilTag code | No: a 4x4 code needs 6 cells across, so 3 to 4 px per cell, which blur erases | 2 to 10 ms (estimate) | Unreadable at this size; curved nail distorts it |
| Shapes (dot, triangle, stripe) | No: blur turns every shape into a streak | Low | Cannot tell shapes apart in motion |
| One solid color per finger | Yes: a blurred blob keeps its color and centroid | Under 1 ms (estimate) | Only about 4 hues separate well from skin and room lighting |
| Retroreflective sticker + phone torch | Yes: becomes the brightest thing in the frame | Under 1 ms (estimate) | Needs the torch on; identity must come from position, not color |

**Why so cheap.** Android delivers YUV frames, and color lives in the half-resolution U and V planes. At 1280 x 720 that is 640 x 360 = 230k pixels; a table lookup per pixel, then connected components on the hits, is a few hundred thousand simple operations. With a search window around each finger's predicted position, it drops to a few thousand. In Rust this is plain loops (or the `imageproc` crate for connected components); a GL shader on the camera texture also works if frames stay on the GPU.

**Robustness problems, in order of severity:**

1. **Camera angle and occlusion.** Nails face up. A side view (best for seeing the press motion) sees them edge-on, and the thumb nail faces sideways. A camera in front of the hands, low and tilted down roughly 20° to 35°, sees the nails obliquely and still sees the press as a mostly vertical image motion. This geometry must be tested first.
2. **Finger identity.** Red, orange, pink and beige overlap skin hues. Green, cyan, blue and magenta separate well; a fifth color is marginal. Using the same 5 colors on both hands and telling hands apart by side of the frame works until the hands cross. Fallback: identity from left-to-right order plus frame-to-frame tracking.
3. **Lighting.** Short exposure indoors raises ISO, so chroma gets noisy, and warm bulbs shift hues. Lock auto white balance and exposure after calibration (Camera2 has AWB and AE lock), and calibrate color thresholds from the recording round rather than hard-coding them.
4. **False positives.** Any object of the same color in view becomes a fingertip. Crop to the playing area and ignore blobs far from the predicted positions.
5. **Practicality.** Painting 10 nails before each session is friction. Removable stickers or thin rings or caps on fingertips do the same job.

The retroreflective route is the classic motion-capture approach and gives the cleanest signal, because the phone's torch sits next to the lens, which is the condition retroreflectors need. I have not verified that the torch can stay on during a constrained high-speed capture session on your phone; check it.

## Press detection

A single camera cannot measure depth, so a press must be read from vertical image motion of each fingertip against a per-finger threshold learned in calibration. Neither idea solves this by itself; it depends on camera placement.

1. **Calibrate.** For each finger, record its image height at rest and when pressed. This defines a press line per finger (with a hysteresis band so jitter does not retrigger).
2. **Track** each fingertip's height y and vertical speed v at 120 fps, smoothed with a One Euro or constant-velocity Kalman filter.
3. **Trigger** a note-on when y crosses the press line while moving down faster than a minimum speed. Use v at crossing as the note velocity.
4. **Release** when the finger rises back above the upper edge of the band.

Trigger on the crossing, not on the finger stopping. In air there is no hard stop, so a "stop" is soft and detected late; the crossing fires within about one frame (8 ms at 120 fps).

Known problems:

- **Finger enslavement.** When one finger presses, its neighbours move too, especially ring and little finger (Häger-Ross & Schieber, J. Neurosci. 2000; cited from memory). Expect false triggers on adjacent fingers. Mitigation: require the pressing finger to move clearly more than its neighbours.
- **Depth ambiguity.** With the front, tilted-down camera, a finger sliding forward also moves down in the image. Calibration per finger reduces this but does not remove it.
- **No physical stop.** Consider playing on a tabletop. A real surface gives a sharp stop, consistent press depth and tactile feedback, which improves both detection and playability. It changes the concept from "air" piano, so it is your call.

## Hybrid options and a first test

The recommended design splits the work by speed: markers every frame for timing, MediaPipe a few times per second for meaning.

| Option | Fast path (every frame, 120 fps) | Slow path | Fit |
| --- | --- | --- | --- |
| A. Markers only | Color blobs, tracked with prediction | None | Simplest; fails when blobs merge, vanish or get swapped |
| B. Markers + slow MediaPipe (recommended) | Color blobs | MediaPipe at 10 to 15 Hz on a downscaled frame, on another thread | MediaPipe re-assigns finger identity and rejects stray blobs; latency is set by the fast path |
| C. Markerless: MediaPipe + optical flow | Lucas–Kanade flow on fingertip patches | MediaPipe at 30 Hz | No paint, but bare skin has little texture, so flow drifts during fast strikes |

In option B, the slow path must never block the fast path: it only corrects the fast tracker's labels when they disagree for several MediaPipe frames in a row.

One caveat from your camera route: if frames reach the CPU through a video encoder and decoder, that round trip may add more latency than marker tracking saves. Measure camera-to-sound latency end to end, not just per-frame compute.

**Test before building anything (one evening):**

- [ ] Put four colored stickers or nail paint (green, cyan, blue, magenta) on one hand, plus a strip of retroreflective tape on another finger.
- [ ] Mount the phone in front of the hand, low and tilted down about 25°, and record with the stock camera's slow-motion mode (120 fps or more), torch on for part of the clip.
- [ ] Run a short desktop script (Python with OpenCV, or Rust with the `image` crate) that thresholds U and V and finds blobs. Check: is every marker found in every frame, do colors stay separable during fast strikes, how big is the blur, and is the press visible as a vertical motion?
- [ ] Only if that passes, decide between option A and B and design the Rust core around it.

## Sources

- [Bazarevsky, Zhang et al., MediaPipe Hands: On-device Real-time Hand Tracking, arXiv:2006.10214 (2020)](https://arxiv.org/abs/2006.10214): tracking scheme and Table 3 latencies.
- [Google, MediaPipe Hand Landmarker guide](https://developers.google.com/edge/mediapipe/solutions/vision/hand_landmarker): Pixel 6 pipeline latency; palm detection skipped in video and live-stream modes.
- [Wang & Popović, Real-Time Hand-Tracking with a Color Glove, SIGGRAPH 2009](https://people.csail.mit.edu/rywang/handtracking): precedent for color-coded hand tracking.
- Häger-Ross & Schieber, Quantifying the independence of human finger movements, J. Neurosci. 20(22), 2000: finger enslavement (cited from memory, page not opened).

Pixel sizes, blur and per-frame costs for markers are my estimates from geometry and an assumed 1 m/s fingertip speed, not measurements.
