# Final Android port report

Date: 2026-08-05 (America/New_York)

## Repository and baseline

- Android repository: `C:\Users\user\Documents\999-vault-android`, independent Git repository on `main`.
- Integrated implementation commit: `abf495358d3748ec4093f3eca205b14389f38a71`.
- Desktop behavioral baseline: `3b24ac807c93c3030cacd814ae605d30b90b2799`.
- The desktop repository remains at that commit with no source-code modifications. Its only untracked file is the user-supplied `ANDROID_PORT_ULTRACODE_PROMPT.md`.
- Package/application ID: `com.vault999.android`; visible name: `999 Vault`.

## Delivered result

The repository contains a native Kotlin, single-Activity Jetpack Compose application with a Media3 `MediaSessionService`, Room/DataStore durability, Keystore AES-GCM account-session storage, exact-origin networking, signed-out local library behavior, queue/Listen/radio playback, archive/catalog/search/viewers, device and cloud playlist state, downloads and collection ZIP processing, SAF storage, Wrapped, settings/account/credits, deterministic fixture coverage, Baseline Profile generation, and Macrobenchmarks. It contains no WebView shell or cross-platform runtime.

The validated parity inventory has 63 unique features: 4 exact, 47 adapted, 9 deferred, and 3 not applicable. Every entry has status `verified`; no inventory item is left partial or unclassified.

## Verification results

Final results are all green:

- `powershell -ExecutionPolicy Bypass -File .\scripts\verify.ps1`: passed from `clean` through parity, tests, lint, debug/fixture/release APKs, release AAB/R8, benchmark assembly, and dependency reports.
- JVM/unit tests: 107 passed, 0 failed, 0 errors, 0 skipped across 18 suites.
- Lint: 0 errors, 35 warnings. Warnings are non-blocking toolchain/API advisories; no lint baseline suppresses product defects.
- Connected physical-device tests: 8 passed, 0 failed, 0 skipped (4 app navigation/playback, 3 downloads/storage, 1 Room migration).
- Macrobenchmark: `OK (4 tests)` with 10 iterations per journey and 40 final Perfetto traces.
- Baseline Profile generator: `OK (1 test)`; 81,243 rules covering startup, populated My Music, Archive, Listen, and Now Playing.
- SAF revoke/regrant: passed with typed permission loss, prior-file preservation, and successful replacement after regrant.
- Mixed resilience session: 32 minutes, one Activity, one MediaSession, no WebViews, no filtered errors/ANRs/StrictMode/secret lines, 234,471 KiB total PSS.
- Forced process death: passed; one paused queue/session restored without autoplay, duplication, or corruption.
- Real socket cancellation: passed; partial-file length remained unchanged 5.2 seconds after cancellation.
- `node scripts/validate-parity.mjs`, `git diff --check`, shipping-manifest isolation, and serial/unexpected-personal-path audit passed. The mandated desktop source path remains documented.

Focused failures found while iterating—SAF provider error classification, asynchronous benchmark seeding, and debug lint visibility of the profileable-only seed—were repaired and rerun. No failure or skipped test remains in the final gates.

## Physical device and performance

The final gate used a Samsung SM-S948U on Android 16/API 36, arm64-v8a, 1080 × 2340 at 450 dpi, locale en-US. The device serial is intentionally omitted.

| Measurement | Result | Target |
|---|---:|---:|
| Cached cold TTID median | 164.5 ms | < 1.5 s |
| Cached cold TTFD median | 164.5 ms | < 2.5 s |
| Catalog steady-scroll CPU frame P95 | 6.83 ms | < 16.7 ms |
| My Music steady-scroll CPU frame P95 | 4.75 ms | < 16.7 ms |
| Open Now Playing CPU frame P95 | 14.40 ms | reported journey |
| Mixed-session total PSS | 234,471 KiB | < 350 MiB |

Exact percentiles and trace names are in `qa/artifacts/20260805-200308/device/performance-summary.md` and `benchmark-final/`.

## Deferred parity items

These nine items are deliberately deferred with adjacent primary behavior implemented and verified:

1. Saving displayed cover art to MediaStore/SAF.
2. Equalizer controls when no safe audio-effect session is exposed.
3. Speculative Listen audio-byte preloading (eight-ahead/eight-recent candidate state is implemented).
4. Named desktop bulk-download preset combinations.
5. Basename-based best-available-audio preset selection.
6. Compact/rendered/original bulk-artwork preset packaging.
7. Assisted device-playlist-to-cloud migration.
8. Custom cloud-playlist covers pending an owned API contract.
9. Public playlist sharing pending authorization/privacy/revocation contracts.

The three not-applicable mappings are desktop window placement restoration, Discord IPC Rich Presence, and Windows NSIS installer/shortcut behavior.

## External and known limitations

- Live OAuth was not opened because no deployed owned account-service origin or test account was supplied. Deterministic encrypted-session, refresh, cached-offline, 401, conflict/idempotency, listening sync, and logout-preservation coverage passes.
- No wired headset or Bluetooth accessory was attached. MediaSession transport, media-button dispatch, audio focus/noisy-route policy, background controls, and Activity recreation are covered without claiming accessory firmware validation.
- The real multi-gigabyte production Compilation archive was not downloaded to avoid consuming user bandwidth/storage. Small deterministic and Zip64 fixtures prove streaming, traversal protection, checkpoint/recovery, cancellation, and bounded-memory behavior.
- Remaining product limitations are enumerated in `docs/known-limitations.md`. No critical or reproducible major defect remains in the tested primary paths.

## Outputs and evidence

- Debug APK: `app/build/outputs/apk/debug/app-debug.apk`
- Fixture APK: `app/build/outputs/apk/fixture/app-fixture.apk`
- Release APK: `app/build/outputs/apk/release/app-release-unsigned.apk`
- Release AAB: `app/build/outputs/bundle/release/app-release.aab`
- R8 mapping: `app/build/outputs/mapping/release/mapping.txt`
- Baseline Profile: `app/src/main/baseline-prof.txt`
- Unit/lint/connected reports: module `build/reports/` and `build/outputs/androidTest-results/` directories
- Dependency reports: `qa/artifacts/dependency-report.txt` and `qa/artifacts/sbom-style-dependency-tree.txt`
- Physical evidence: `qa/artifacts/20260805-200308/device/`
- Human and machine parity records: `docs/platform-differences.md` and `docs/platform-parity.yaml`

Generated QA material remains ignored according to `qa/artifacts/README.md`; it was scrubbed of the device serial, credentials, account data, and personal media before final reporting.
