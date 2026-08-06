# Device test matrix

No serial number is recorded. Final generated evidence is under `qa/artifacts/20260805-200308/device/`.

| Field | Value |
|---|---|
| Manufacturer/model | Samsung SM-S948U |
| Android/API | Android 16 / API 36 |
| ABI | arm64-v8a |
| Native size/density | 1080 x 2340 / 450 dpi |
| Locale | en-US |
| Available `/data` storage | 123 GiB available of 220 GiB at initial inspection |
| Exercised variants | debug, fixture, and profileable non-minified release |
| Device mutation policy | no data clear, permission reset, uninstall, account mutation, or global setting changes; the user-selected empty SAF fixture directory remains on the device |

## Final physical-device gate

- Eight connected tests passed: four app navigation/playback tests, three transfer/storage tests, and one Room migration test. The cancellation test used a real IPv4 loopback socket and proved unchanged partial-file length 5.2 seconds after cancellation.
- Portrait and landscape captures cover all four top-level destinations plus Settings, Downloads, Wrapped, Credits, Files, Listen content, Now Playing, and 200% font scale. The final Now Playing captures show output/volume plus the wrapped Favorite, Lyrics, Download, and Queue actions.
- SAF revocation/regrant passed end to end. The pre-existing final remained readable, loss of the persisted tree grant produced the typed permission boundary, and regrant published the replacement without leaving a temporary document.
- A 32-minute mixed foreground/background/navigation/playback session completed with one Activity, one active MediaSession, one queue item, zero WebViews, no filtered error/ANR/StrictMode/secret log lines, and 234,471 KiB total PSS (below 350 MiB).
- Forced process death and relaunch restored one paused queue/session without autoplay, duplication, or corruption.
- The final 10-iteration profileable suite passed all four Macrobenchmarks. Cached cold TTID/TTFD median was 164.5 ms; Catalog P95 CPU frame time was 6.83 ms; My Music P95 was 4.75 ms; opening Now Playing P95 was 14.40 ms. Perfetto traces and benchmark JSON are in `device/benchmark-final/`.
- Baseline Profile generation passed after startup, populated My Music, Archive, Listen, and Now Playing journeys; the checked-in profile contains 81,243 rules.

## Evidence index

- `resilience-summary.md`, `resilience-logcat.txt`, and `resilience-final.png`
- `process-death-summary.md` and `process-death-recovery.png`
- `saf-revocation-summary.md` and the `saf-*` screenshots
- `performance-summary.md` and `benchmark-final/`
- `now-playing-final3.png` and `now-playing-actions-final.png`
- Gradle reports under `app/build/reports/`, `core/*/build/reports/`, and `benchmark/build/reports/`
