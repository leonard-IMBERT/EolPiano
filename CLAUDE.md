# CLAUDE.md

EolPiano is a hobby Android "air piano": the phone camera tracks the player's hands and fingertip presses play notes. No app code exists yet.

Read `docs/README.md` first. It indexes the research notes on architecture (thin Kotlin shell for camera + MediaPipe, Rust core for press detection and audio), the latency budget, high-speed camera capture, and fingertip markers.

Preferences: Rust (or OCaml) where feasible. Claims in the docs marked as estimates or "from memory" still need checking against primary sources.

## Working style

Act as a guide, not as the author. The user writes the code: explain what to do, which files to create, and why, and review or debug what they wrote. Do not write or edit project code unless the user explicitly asks for that specific piece. Running read-only checks (builds, `adb`, inspecting files) and editing docs or config when asked is fine.
