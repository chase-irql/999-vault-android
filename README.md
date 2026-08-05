# 999 Vault for Android

Native Android client for the JuiceWRLDAPI archive, built with Kotlin, Jetpack Compose, Material 3, Room, DataStore, and Media3.

> **Independent project:** 999 Vault is not affiliated with, endorsed by, sponsored by, or operated by the JuiceWRLDAPI team. Music, artwork, names, and trademarks belong to their respective rights holders.

## Prerequisites

- JDK 17
- Android SDK Platform 37 and current 37.x build tools
- One authorized Android device for connected and performance verification
- Node.js 22+ only for the zero-dependency parity-schema validator

## Build and verify on Windows

```powershell
.\gradlew.bat clean
.\gradlew.bat testDebugUnitTest
.\gradlew.bat lintDebug
.\gradlew.bat assembleDebug
.\gradlew.bat bundleRelease
.\scripts\verify.ps1
```

With exactly one authorized USB device:

```powershell
.\scripts\verify-device.ps1
```

The scripts never uninstall the app, clear its data, reset permissions, or change global device settings. The debug APK installs with `adb install -r`.

## Configuration

Production archive traffic is restricted to `https://juicewrldapi.com`. Account traffic is restricted to the owned 999 Vault service configured at build time. Secrets and signing material are never stored in this repository. A release owner supplies signing configuration outside Git.

The app works signed out: Catalog, archive browsing, playback, queue, local favorites and playlists, downloads, listening history, and local Wrapped remain device data. Signing in adds canonical cloud likes, playlists, and acknowledged listening events without replacing local data.

Downloads use app-specific external storage by default. A user can choose a Storage Access Framework document tree; the app stores and revalidates the persistable URI permission and never presents a content URI as a fake path.

See [architecture](docs/architecture.md), [platform differences](docs/platform-differences.md), [known limitations](docs/known-limitations.md), and the [device matrix](docs/device-test-matrix.md).

Generated QA outputs live under `qa/artifacts/` and are ignored except for its policy file. Curated, non-sensitive evidence can be deliberately copied into `qa/evidence/` and committed.

