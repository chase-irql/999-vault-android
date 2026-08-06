# 999 Vault for Android

Native Android client for the JuiceWRLDAPI archive, built with Kotlin, Jetpack Compose, Room, DataStore, Media3, WorkManager/UIDT, OkHttp, and Coil.

> **Independent project:** 999 Vault is not affiliated with, endorsed by, sponsored by, or operated by the JuiceWRLDAPI team. Music, artwork, names, and trademarks belong to their respective rights holders.

## Prerequisites

- Windows PowerShell, Git, and Node.js 22 or newer.
- JDK 17. The verified local distribution is Eclipse Adoptium 17.0.20.
- Android SDK Platform 37, Build Tools 37.0.0, and current Platform Tools.
- One authorized physical Android device for the final connected and performance lane.

Set the local tool paths for the current PowerShell session:

```powershell
$env:JAVA_HOME = 'C:\Program Files\Eclipse Adoptium\jdk-17.0.20.8-hotspot'
$env:ANDROID_HOME = Join-Path $env:LOCALAPPDATA 'Android\Sdk'
```

`local.properties` may contain only the local `sdk.dir`; it is ignored by Git.

## Build and verify on Windows

The complete device-independent lane validates parity, cleans the tree, runs JVM and Android unit tests, runs lint, assembles debug/fixture/release APKs, builds the release AAB with R8, assembles the benchmark app, and writes dependency reports under `qa/artifacts/`:

```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\verify.ps1
```

Equivalent focused commands are:

```powershell
node .\scripts\validate-parity.mjs
.\gradlew.bat test testDebugUnitTest lintDebug
.\gradlew.bat assembleDebug assembleFixture assembleRelease bundleRelease :benchmark:assemble
```

With exactly one authorized USB device, run:

```powershell
& "$env:ANDROID_HOME\platform-tools\adb.exe" devices -l
powershell -ExecutionPolicy Bypass -File .\scripts\verify-device.ps1
```

The device script uses `adb install -r`; it does not clear app data, reset permissions, or change global device settings. It runs the deterministic transfer fixture, connected UI/playback/database tests, Baseline Profile generation, and the Macrobenchmark lane. Manual SAF revocation/regrant steps are in [download-fixture-harness.md](docs/download-fixture-harness.md).

## Variants and outputs

- `debug`: live archive, debuggable, QA-only orientation/font-scale intent extras.
- `fixture`: deterministic offline UI data for screenshots and instrumentation.
- `release`: live archive, R8/minification/resource shrinking, unsigned unless signing is supplied externally.

Primary outputs are `app/build/outputs/apk/debug/app-debug.apk`, `app/build/outputs/apk/release/app-release-unsigned.apk`, `app/build/outputs/bundle/release/app-release.aab`, and `app/build/outputs/mapping/release/mapping.txt`.

## Configuration and OAuth

Production archive traffic is restricted to the exact HTTPS origin `https://juicewrldapi.com`; redirects and cleartext are rejected. Account traffic is restricted to an owned 999 Vault origin supplied at build time:

```powershell
.\gradlew.bat assembleDebug -PvaultAccountOrigin=https://accounts.example.invalid
```

The account service implements [api-contract.md](docs/api-contract.md), allows the exact callback `vault999://auth/callback`, issues rotating opaque app sessions, and keeps Discord client secrets and Discord tokens on the backend. The app uses Custom Tabs, PKCE S256, one-use state/callback handling, and an Android Keystore AES-GCM envelope. Android builds use the same staging account service as desktop by default; pass `-PvaultAccountOrigin=https://your-owned-origin.example` to select another exact HTTPS origin.

## Storage and signed-out use

The app is fully useful signed out: live/cached Catalog, archive browsing and viewers, playback/queue/Listen/radio, app-specific or SAF downloads, device favorites/playlists, listening history, and local Wrapped. Signing in adds separately labelled cloud likes/playlists and acknowledged listening-event sync; logout hides cloud projections without deleting device data.

Downloads use app-specific external storage by default. A user may select a Storage Access Framework tree; the persisted URI grant is revalidated before writes and extraction. The app never requests `MANAGE_EXTERNAL_STORAGE` and never presents a `content://` URI as a filesystem path. App-specific single-file downloads can play directly from My Music; SAF/collection outputs remain visible in Downloads and are reopened through Android-owned storage UI.

## Architecture and evidence

The single Activity renders Compose destinations, while one Media3 `MediaSessionService` owns playback beyond Activity lifetime. Repositories validate exact-origin network data and project durable state to Room/DataStore; transfers persist state before UIDT/WorkManager scheduling. See [architecture.md](docs/architecture.md), [platform differences](docs/platform-differences.md), [source baseline](docs/source-baseline.md), [known limitations](docs/known-limitations.md), and the [device matrix](docs/device-test-matrix.md).

Generated screenshots, logs, traces, reports, APKs, and device metadata live under ignored `qa/artifacts/<timestamp>/`. The policy in [qa/artifacts/README.md](qa/artifacts/README.md) requires removing serials, credentials, account data, and personal media before any evidence is versioned.
