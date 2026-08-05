# Android versus desktop

This document evolves with `platform-parity.yaml`; the machine inventory is canonical.

## Navigation and presentation

Desktop uses a responsive sidebar/window and system tray. Android uses four stable bottom destinations—Archive, Listen, My Music, Search—plus nested Downloads, Wrapped, Settings, Credits, detail, and Now Playing surfaces. A mini-player sits above navigation. This preserves reachability on a phone and replaces close-to-tray with Android Back and system media controls. No desktop window geometry migrates.

## Playback

Desktop Web Audio/Electron playback becomes one Media3 `MediaSessionService`. Android system notification, lock screen, Bluetooth/media buttons, audio focus, and noisy-route behavior replace tray controls. Queue semantics and catalog-wide Next remain equivalent; player ownership is adapted to Android lifecycle rules.

The desktop ten-band equalizer becomes a capability layer over the device audio effect. The ten control points map deterministically to available bands; unsupported devices show an explanation and playback continues.

Discord Rich Presence is `not_applicable`: Android has no equivalent for the desktop local Discord IPC integration. Discord account sign-in is a separate OAuth feature and remains supported.

## Downloads and storage

Windows paths and folder pickers become app-specific external storage or a user-selected SAF tree. Android never fabricates filesystem paths for `content://` locations. UIDT jobs/foreground workers and notifications replace desktop background-window/tray behavior. Existing Windows-local files are not imported automatically.

## Account and library

Electron `safeStorage` becomes an Android Keystore AES-GCM envelope. Device favorites/playlists/downloads/history remain when signed out or logged out; canonical cloud data is visibly labeled as synced, pending, errored, or device-only.

## Deferred platform surfaces

Android Auto, Cast, Wear OS, widgets, picture-in-picture, public playlist sharing, custom cloud covers, Play Store rollout, iOS, and a direct Windows-file migration utility remain deferred until required phone scope is verified.

