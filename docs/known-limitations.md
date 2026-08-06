# Known limitations and external blockers

No critical or reproducible major defect is known in the tested primary fixture paths. The following limitations are explicit in the validated parity ledger:

- Cover artwork is displayed and cached, but exporting a cover to MediaStore/SAF is deferred until its publication contract and device tests exist.
- Equalizer controls remain capability-disabled with an explanation because a safe audio-effect session is not exposed; playback is unaffected.
- Listen keeps eight candidates ahead and eight recent entries, but speculative audio-byte preloading is deferred pending measured storage and metered-network policy.
- Exact archive selection, recursive directory ZIP, and full-collection download work; named desktop bulk presets, basename-based best-audio selection, and compact/rendered/original artwork preset packaging are deferred conveniences.
- Device and cloud playlists remain deliberately distinct; assisted device-to-cloud migration is deferred to avoid unsafe title matching.
- Custom cloud-playlist covers and public playlist sharing are deferred until the owned account API specifies validation, privacy, authorization, and revocation.
- App-specific single-file downloads can be played from My Music. SAF and extracted collection items remain accessible through Downloads/storage UI but are not yet indexed into individual playable library rows.
- The full-collection workflow is verified with deterministic small and Zip64-format fixtures, streamed extraction, checkpoint recovery, and bounded buffers. The real multi-gigabyte production Compilation archive was not downloaded during this gate to avoid consuming the user's bandwidth and storage.
- Live OAuth requires an owned deployed account service and user-present browser interaction. Deterministic auth/cloud staging tests cover refresh, corruption, offline, 401 replay, conflicts, and logout preservation when that service is unavailable.

Live OAuth was not exercised because no deployed owned account-service origin or test account was supplied. No browser was opened and no callback token was captured. Deterministic tests cover the account state machine, encrypted restoration, refresh, cached-offline behavior, 401 replay, conflict/idempotency handling, pending listening sync, and logout preservation.

No wired headset or Bluetooth accessory was attached during the final device gate. Android media-button dispatch, MediaSession transport, audio focus/noisy-route policy, background controls, and Activity recreation are covered by connected service tests and the mixed device session; accessory-specific firmware behavior is not claimed.
