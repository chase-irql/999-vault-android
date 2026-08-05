# Architecture

## Module graph

`:app` owns the single activity, navigation, top-level dependency composition, and permission UX. It depends inward on feature-neutral core interfaces. `:core:model` has no Android dependency. Network, database, preferences, authentication, playback, downloads, design system, and testing concerns have separate modules. `:benchmark` is an external test application.

Dependency direction is UI -> use cases/repositories -> data sources. Compose never calls a DAO, HTTP client, file API, scheduler, or player directly.

## State authority

- JuiceWRLDAPI: archive files, canonical songs/categories/eras/lyrics, artwork/media URLs, radio, and ZIP jobs.
- Owned 999 Vault account API: current account, acknowledged canonical likes/playlists/listening IDs, and revisions.
- Room/DataStore/files: local library, queue snapshot, settings, download checkpoints, cached projections, and pending mutations/events.
- `VaultPlaybackService`: active timeline, current item and position, shuffle/repeat, playback state, and audio focus.
- Compose: disposable rendering state only.

## Data flow

Repositories validate remote DTOs and project them transactionally into Room. ViewModels expose immutable `StateFlow` values and accept explicit actions. Offline reads continue from Room; recoverable failures preserve the last valid projection and expose a retry action.

## Playback lifecycle

One `MediaSessionService` owns one ExoPlayer. Activities bind through a MediaController. A durable queue snapshot permits user-initiated resumption after process death without autoplay on cold launch. Audio focus, noisy-route handling, media keys, notification controls, and playback resumption terminate at the service boundary.

## Storage and transfer recovery

`VaultStorage` has app-specific and SAF implementations. Transfer state is persisted before scheduling. API 34+ user-started transfers use a user-initiated data-transfer job; API 26-33 uses a foreground worker. Range resumption requires matching validator and total length. ZIP extraction validates every entry, streams bytes, and checkpoints completed entries.

## Security

HTTP clients enforce exact HTTPS origins and bounded responses. Account tokens live only in an AES-256-GCM Android Keystore envelope in app-private storage. Logs and saved UI state never contain tokens. Release cleartext is disabled, backup rules exclude sessions/private caches, exported components are minimized, and release builds use R8.

