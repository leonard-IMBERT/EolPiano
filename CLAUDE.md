# CLAUDE.md

EolPiano is a hobby Android "air piano": the phone camera tracks the player's hands and fingertip presses play notes. No app code exists yet.

Read `docs/README.md` first. It indexes the research notes on architecture (thin Kotlin shell for camera + MediaPipe, Rust core for press detection and audio), the latency budget, high-speed camera capture, and fingertip markers.

Preferences: Rust (or OCaml) where feasible. Claims in the docs marked as estimates or "from memory" still need checking against primary sources.
