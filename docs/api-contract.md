# API contract and Android mapping

## Authority and scope

This inventory was derived from the Windows desktop repository at commit
`3b24ac807c93c3030cacd814ae605d30b90b2799` on 2026-08-05. The inspected
sources were:

- `api-client.js`
- `electron/main.js`, `electron/preload.cjs`, and `electron/renderer/app.js`
- `electron/account-api.js`, `electron/auth-session.js`, and
  `electron/cloud-library.js`
- `electron/catalog.js`, `electron/security.js`, and
  `electron/collection-download.js`
- `scripts/zip-jobs-test.mjs`
- `docs/account-api-contract.md`, `api/src/index.js`, and `api/README.md`

"Active" below means the pinned desktop application invokes the route or
constructs its URL. "Compatibility only" means the shared desktop API client
defines the route, but the pinned application's main/preload path does not use
it. Compatibility-only routes are documented so they are not mistaken for
missing discoveries; they do not require an Android UI until the parity matrix
assigns them a feature.

The fields below are the exact consumer contract at the pinned commit. Where
the upstream archive API has no repository-owned schema, the document says
"pass-through" and lists every field the desktop actually reads. Android must
validate those consumed fields and ignore unknown fields; it must not infer
that an unconsumed upstream field is stable.

## Origins and common transport policy

### JuiceWRLDAPI archive client

- Production origin: `https://juicewrldapi.com`.
- API base path: `/juicewrld`.
- No route uses authentication.
- The Android release client must accept only HTTPS and the exact production
  origin. It must reject URL credentials and cross-origin redirects.
- The archive client must not expose a generic URL or generic route method to
  UI code. Route templates belong in `:core:network`.
- JSON response bodies must be bounded. The desktop did not bound them. Use a
  conservative per-route cap and stream file, audio, artwork, and ZIP bodies.
- Unknown JSON fields are ignored. Malformed JSON, wrong top-level shapes, and
  values outside the limits in this document are contract errors, not empty
  successful results.
- JSON calls send `Accept: application/json`; calls with a body send
  `Content-Type: application/json`. Streaming calls send an appropriate narrow
  accept value (`audio/*`, `image/*`, ZIP/octet-stream) while remaining tolerant
  of the desktop server's generic binary content type.
- Redact query values that can expose private local behavior, and never log a
  complete download URL. Logging may include the route template, status, byte
  count, and operation ID.

### 999 Vault account client

- Desktop default at the pinned commit:
  `https://vault999-account-staging.2lt.workers.dev`.
- Release builds must use one build-configured, 999 Vault-owned HTTPS origin.
  The origin must contain no credentials, path, query, or fragment.
- Debug fixtures may use explicit loopback HTTP. That exception must not be
  reachable from a release build.
- Cross-origin and same-origin HTTP redirects are rejected for API calls. The
  OAuth authorization URL is returned as data and opened separately in a
  Custom Tab after exact-origin validation.
- JSON request and response limit: 2 MiB. The client checks serialized request
  bytes and streamed response bytes, not Java/Kotlin character count.
- Default request timeout in the desktop client was 12 seconds, clamped to
  250 ms through 60 seconds. Android may use operation-specific values, but
  tests must cover slow headers and slow bodies and all values must be bounded.
- Account errors have `{ code, message, retry_after_seconds? }`. Backend
  `message` is never rendered directly. Android maps the sanitized `code`, HTTP
  status, and retry metadata to local user-facing copy.
- Requests send `Accept: application/json`; requests with JSON send
  `Content-Type: application/json`. Successful nonempty bodies must be valid
  JSON even when a server omits or mislabels Content-Type.

Expected account status mapping is:

| HTTP status | Default local class | Retry handling |
| --- | --- | --- |
| 400 | `validation` | Do not retry without corrected input. |
| 401 | `unauthorized` | Serialize one refresh when applicable; invalid refresh ends cloud auth. |
| 403 | `forbidden` | Do not retry automatically. |
| 404 | `not_found` | Reconcile a deleted record; do not retry unchanged. |
| 409 | `conflict` | Refetch/rebase; never treat as a transport retry. |
| 413 | `too_large` | Reduce the request; do not retry unchanged. |
| 429 | `rate_limited` | Honor bounded `Retry-After` for safe or queued-idempotent work. |
| 5xx | `service_failure` | Retry only safe reads or persisted idempotent operations. |
| other non-2xx | `http_error` | Fail closed and preserve valid cache/local state. |

## Canonical normalization

### Song

Archive song payloads may be a top-level song or, for some radio/file payloads,
be nested under `song`. The normalized Android model is:

| Android field | Accepted archive field(s) | Rule |
| --- | --- | --- |
| `id` | `id` | Positive safe integer; otherwise absent. This is the canonical account-sync ID. |
| `publicId` | `public_id`, `publicId` | Positive safe integer; otherwise absent. |
| `title` | first nonblank `track_titles[]`, then `name` | NFKC, single-line, at most 300 chars; fallback `Unknown track`. |
| `aliases` | remaining `track_titles[]`, `name` | Case-insensitive unique, excluding title, at most 50. |
| `path` | `path` | Relative slash-separated archive path, at most 4096 chars; no empty/dot/dot-dot segment, control character, backslash traversal, or leading slash. |
| `artist` | `credited_artists`, `artist` | Single-line, at most 300 chars; fallback `Juice WRLD`. |
| `durationSeconds` | `length`, `duration` | Number of seconds or `M:SS`/`H:MM:SS`, 0 through 86400. |
| `category` | `category` | `released`, `unreleased`, `unsurfaced`, or `recording_session`; otherwise `other`. |
| `era` | `era` | Normalize with the Era contract below. |
| `artworkUrl` | `image_url`, `imageUrl` | Exact `https://juicewrldapi.com` origin, no credentials; otherwise absent. |
| `producers` | `producers` | Single-line, at most 500 chars. |
| `streamUrl` | derived from valid `path` | Exact-origin `/juicewrld/files/download/?path=...`; never trust a separately supplied URL. |

The song-detail view additionally consumes pass-through fields `inferred`,
`album`, `album_artists`, `featured_artists`, `engineers`,
`recording_locations`, `record_dates`, `release_date`, `session_titles`,
`notes`, `lyrics`, and `synced_lyrics`. Lyrics and notes are plain text and are
never interpreted as HTML.

Title similarity or a filename match must never create a canonical ID. A
path-only archive or local record stays noncanonical and cannot be uploaded to
the account API.

### Era

| Android field | Accepted field(s) | Rule |
| --- | --- | --- |
| `id` | `id` | Positive safe integer. |
| `name` | `name` | Required single-line text, at most 120 chars. |
| `description` | `description` | Single-line text, at most 1000 chars. |
| `timeFrame` | `time_frame`, `timeFrame` | Single-line text, at most 200 chars. |
| `playCount` | `play_count`, `playCount` | Non-negative integer; fallback 0. |

### Archive entry

`GET /files/list-all/` entries consumed by the desktop are `path`, `type`,
`name`, `size`, `extension`, and `modified`. `type` is `directory` or a file
value; `size` is a non-negative byte count. Android validates `path` with the
same rules as a song path, derives a safe display name when needed, and treats
server extensions and timestamps as display metadata rather than path
authority.

### Account and playlist

Account fields are `id`, `display_name`, `discord_id`, `discord_username`, and
`discord_avatar`. Tokens never enter the account model.

Playlist records contain exactly the consumed fields `id`,
`client_migration_id`, `name`, `description`, `cover_url`, `song_ids`,
`revision`, `created_at`, and `updated_at`. IDs and revisions are opaque text.
Names are 1-80 characters, descriptions are at most 500 characters, and song
IDs are unique positive integers. The owned Worker permits at most 10,000 songs
per playlist. `cover_url` is accepted only when it is a credential-free HTTPS
URL.

### Listening event

The wire fields are `id`, `song_id`, `played_at`, `listened_seconds`,
`duration_seconds`, and `source`. `id` is a durable UUID-style opaque ID,
`song_id` is a positive canonical ID, `played_at` is an ISO-8601 instant,
`listened_seconds` is finite and from 0 through 86400, and
`duration_seconds` is null or finite and greater than 0 through 86400. Owned
Worker source values are `catalog`, `playlist`, `radio`, `downloaded`, `files`,
`queue`, and `unknown`; unknown client values are stored as `unknown`.

## JuiceWRLDAPI route inventory

### Active JSON and metadata routes

| Client function / caller | Method and route | Request | Response consumed | Android mapping |
| --- | --- | --- | --- | --- |
| `health()` via preload | `GET /juicewrld/health/` | None | Pass-through object; desktop reads `status == "ok"`. | `ArchiveHealthDataSource`; never gate cached/offline use solely on this probe. |
| `stats()` via preload | `GET /juicewrld/stats/` | None | `total_songs`; `category_stats.released`, `.unreleased`, `.unsurfaced`, `.recording_session`. | Catalog summary cache. |
| `songs(filters)` via catalog | `GET /juicewrld/songs/` | `page` 1-10000; `page_size` 1-100; optional `searchall` <=250 normalized chars, `category`, and era **name** in `era`. | `{count,next,previous,results}`; desktop also accepts `items`. Results normalize as Song. | Paged catalog repository and transactional Room projection. Ordering is local because this endpoint was documented in desktop code as not accepting `ordering`. |
| `songs(filters)` via lyrics | Same route | `lyrics`, `page`, `page_size`, `file_names_array=true`. Desktop input page size is bounded by its lyrics sanitizer. | `{count,results|items}`; summary consumes song identity/path/name, `lyrics`, and a plain-text excerpt. | Lyrics search repository; do not index/render HTML. |
| `songs(filters)` via path/name detail fallback | Same route | `searchall=<name>`, `page_size=100`, `file_names_array=true`. | Candidate selected by exact path, then case-insensitive name. | Android should prefer canonical ID/path. Name-only selection must not confer cloud identity. |
| `song(id)` | `GET /juicewrld/songs/{id}/` | Positive integer path segment. | Song plus detail fields listed above. | Song detail repository; used for by-ID playlist/Wrapped hydration. |
| `eras(query)` | `GET /juicewrld/eras/` | `page` positive integer. Desktop requests at most pages 1-10. | `{count,next,results}`; desktop also accepts `items`; results normalize as Era. | Fetch until `next` is absent with an explicit safety cap and surface truncation rather than silently hiding it. |
| `playableSongs()` via preload | `GET /juicewrld/playable_songs/` | None | Bare array or `{results|items}` of song/file-like records; a valid path is required. | Endless-listening source; cache and deduplicate by canonical ID/path. |
| `randomRadio()` via preload | `GET /juicewrld/radio/random/` | None | Song/file-like payload; may have nested `song`. | Random discovery repository. |
| `radioLive()` via preload | `GET /juicewrld/radio/live/` | None | `station`, `total_listeners`, `dj_line`, `stream_url`, `queue_preview[]`, `now_playing.title`, `.display`, `.album`, `.elapsed_ms`, `.duration_ms`. | Radio status polling. Validate `stream_url`; otherwise use the exact fallback stream route below. |
| `listAllFiles()` | `GET /juicewrld/files/list-all/` | None | `{items}`; desktop also accepts `{results}`. Entries use the Archive entry contract. | Archive index repository and Room cache. The endpoint is unpaged, so enforce byte/item limits and retain a prior valid cache on failure. |

Archive paging responses may move while browsing. Android deduplicates page overlap
by canonical song ID, then path. It preserves the server `count` only as an
estimate and stops on an absent next page or a short page. A malformed page is
an error and does not replace a valid cached page.

### Active streaming and download routes

| Client function / caller | Method and route | Request / headers | Response consumed | Android mapping |
| --- | --- | --- | --- | --- |
| `streamUrl(path)`, renderer URL | `GET /juicewrld/files/download/?path={path}` | Valid remote path. Playback may omit Range; durable downloads send `Range: bytes={offset}-` only with a stored validator strategy. | Streaming bytes; `content-type`, `content-length`, status 200/206, `Content-Range`, `ETag`, and/or `Last-Modified` where supplied. | Media3 source for streaming; durable transfer adapter for offline files. Cross-origin redirects forbidden. |
| `fileDownload(path)` | Same route | Same as above. Desktop also uses it for bounded text and in-memory media. | Raw body. Desktop text reader rejects announced or measured content over 5 MiB. | Stream media and files; text viewer has a 5 MiB hard cap. Never buffer normal media or downloads in memory. |
| `coverArt(path)`, renderer URL | `GET /juicewrld/files/cover-art/?path={path}` | Valid remote path. | Raw image bytes and `content-type`; desktop derives `.jpg` fallback. | Coil-backed exact-origin image request and explicit artwork download. Bound image bytes and decode dimensions. |
| radio fallback | `GET /juicewrld/radio/stream.mp3` | None. | Continuous audio stream; server `stream_url` may point here. | Media3 live radio item; validate any supplied URL to the archive allowlist. |

### Active server ZIP-job routes

| Client function | Method and route | Request | Response consumed | Android mapping |
| --- | --- | --- | --- | --- |
| `startZip(paths)` | `POST /juicewrld/start-zip-job/` | JSON `{ "paths": [remotePath, ...] }`; full collection sends `Compilation`. | Job ID accepted from `job_id`, `jobId`, or `id`. | Start a persisted `preparing` job. Do not blindly retry without a server idempotency guarantee. |
| `zipStatus(jobId)` | `GET /juicewrld/zip-job-status/{jobId}/?_t={millis}` | Valid opaque job ID path segment; `_t` is cache busting only. | `status|state`, numeric `progress`, `download_url|downloadUrl`, and `error`. Ready aliases observed by desktop: `done`, `completed`, `ready`, `success`, `succeeded`; failure aliases: `cancelled`, `canceled`, `failed`, `error`. | Poll with persisted state and cancellable delay. Unknown state remains preparing with bounded timeout. |
| returned job download | `GET /juicewrld/zip-jobs/{jobId}.zip` | No query. URL must have exact archive origin, exact expected path, no credentials, query, or fragment. | ZIP/octet-stream body and nonzero `content-length`; validators when available. | Durable ZIP transfer with Range+validator resume, disk-space checks, Zip64 validation, and streaming extraction. |
| `cancelZip(jobId)` | `POST /juicewrld/cancel-zip-job/{jobId}/` | No JSON body. | Any 2xx success body is ignored. | Best-effort server cancel plus immediate local socket/stream cancellation. Persist `cancelling` before dispatch; terminal local cancellation does not wait forever for server acknowledgement. |

### Compatibility-only archive routes

These are defined by `api-client.js` but not invoked through the pinned
desktop application's active main/preload path.

| Client function | Method and route | Request | Response / notes | Android status |
| --- | --- | --- | --- | --- |
| `overview()` | `GET /juicewrld/` | None | Unvalidated pass-through JSON/text. | Compatibility only; no generic UI exposure. |
| `playStats()` | `GET /juicewrld/plays/stats/` | None | Unvalidated pass-through JSON. | Compatibility only. |
| `categories()` | `GET /juicewrld/categories/` | None | Unvalidated pass-through JSON. | Compatibility only; Android category enum comes from the pinned catalog semantics. |
| `trackPlay(body)` | `POST /juicewrld/plays/` | Arbitrary JSON in desktop wrapper. | Unvalidated pass-through. | Do not port as-is; account listening events are the Android source of durable play credit. |
| `browse(path,search)` | `GET /juicewrld/files/browse/?path=&search=` | Optional path and search. | Unvalidated pass-through. | Compatibility only; active desktop uses list-all and a local tree. |
| `fileInfo(path)` | `GET /juicewrld/files/info/?path=&_t=` | Path and cache buster. | Unvalidated pass-through. | Compatibility only. |
| `thumbnail(path,size)` | `GET /juicewrld/files/image-thumbnail/?path=&size=` | Default size 256. | Raw image body. | Compatibility only; validate size before any future use. |
| `zipEstimate(paths)` | `POST /juicewrld/files/zip-estimate/` | `{paths}`. | Unvalidated JSON. | Implement behind download estimates only after fixture/live schema verification. Desktop defines but does not use it. |
| `immediateZip(paths)` | `POST /juicewrld/files/zip-selection/` | `{paths}`. | Raw ZIP body. | Active in the ZIP diagnostic client, compatibility-only in the app; not the full-collection production path. |
| `createPlaylist(body)` | `POST /juicewrld/playlists/share/` | Arbitrary desktop body. | Unvalidated pass-through. | Legacy public-share compatibility, not account playlist sync. |
| `sharedPlaylist(shareId)` | `GET /juicewrld/playlists/shared/{shareId}/` | Encoded share ID. | Unvalidated pass-through. | Compatibility only. |
| `sharedPlaylistInfo(shareId)` | `GET /juicewrld/playlists/shared/{shareId}/info/` | Encoded share ID. | Unvalidated pass-through. | Compatibility only. |
| `radioLibrary()` | `GET /juicewrld/radio/library/` | None. | Unvalidated, unpaged JSON; desktop timeout 120s. | Compatibility only; do not fetch without a bound. |
| `fileShares()` | `GET /juicewrld/files/share/` | None. | Unvalidated pass-through. | Compatibility only. |
| `createFileShare(filePath)` | `POST /juicewrld/files/share/` | `{file_path}`. | Unvalidated pass-through. | Compatibility only. |
| `deleteFileShare(id)` | `DELETE /juicewrld/files/share/{id}` | No body; desktop route has no trailing slash. | Unvalidated pass-through. | Compatibility only. |

## ZIP diagnostic-client contract

`scripts/zip-jobs-test.mjs` exercises four archive operations without auth:

- `start`: POST `{paths}` to `/start-zip-job/`; optional watch extracts a job ID
  from `job_id`, `jobId`, or `id`.
- `status` and `watch`: GET `/zip-job-status/{id}/?_t=...`.
- `cancel`: POST with no body to `/cancel-zip-job/{id}/`.
- `direct`: POST `{paths}` to `/files/zip-selection/`, require a response body,
  write `<output>.part`, and rename only after stream completion.

It performs no automatic retry. JSON requests have a 30-second timeout that
includes the response body. Watch defaults to 1.5-second polling and a
10-minute overall maximum. Its terminal state aliases additionally include
`complete`, `finished`, and `ready`. A watch timeout does not cancel the remote
job. The direct stream timeout defaults to one hour and covers the whole body;
failure or cancellation removes the partial. Android fixture tooling should
retain these diagnostic commands or equivalent tests, while production
downloads retain resumable partial state instead of deleting it reflexively.

## 999 Vault account route inventory

Except for auth start, exchange, and refresh, every route requires
`Authorization: Bearer <opaque-access-token>`. Opaque account tokens are not
Discord tokens. Optional `Idempotency-Key` values are 1-128 characters from
`A-Z`, `a-z`, `0-9`, `_`, and `-`.

### Authentication

| Client function | Method and route | Request | Response | Android mapping |
| --- | --- | --- | --- | --- |
| `startAuthorization` | `POST /v1/auth/discord/start` | `{redirect_uri:"vault999://auth/callback",state,code_challenge,code_challenge_method:"S256"}`. | `{authorize_url,expires_at}`. `authorize_url` must be HTTPS and match an exact configured authorization origin; desktop default is `https://discord.com`. | Generate state/verifier on-device, persist pending state securely, open validated URL in Custom Tab. |
| browser callback to backend | `GET /v1/auth/discord/callback?code={code}&state={state}` | Discord/browser operation, not an app API call. | Backend 302 to `vault999://auth/callback?ticket=...&state=...`. | Intent filter accepts only exact scheme `vault999`, host `auth`, path `/callback`; reject duplicate/extra security-sensitive fields and mismatched state. |
| `exchangeAuthorization` | `POST /v1/auth/discord/exchange` | `{ticket,state,code_verifier,redirect_uri:"vault999://auth/callback"}`. | `{access_token,refresh_token,expires_at,user}`. Ticket is single-use and short-lived. | Exchange once, validate full response, then atomically replace encrypted session envelope. |
| `refresh` | `POST /v1/auth/refresh` | `{refresh_token}`. | Rotated `{access_token,refresh_token,expires_at,user}`. | Serialize concurrent refreshes. Refresh within 60 seconds of expiry. A definitive 401 invalid-refresh clears cloud auth; offline/timeout retains cached identity. |
| `logout` | `POST /v1/auth/logout` | Bearer auth, no body. | Owned Worker returns `{}`. | Best-effort revoke. Clear local encrypted session regardless of response while preserving signed-out local library data. |
| `me` | `GET /v1/me` | Bearer auth. | Account object. | Defined by desktop client but not invoked by pinned main; useful only for explicit account revalidation. |

The desktop state machine uses 32 random bytes for state, 64 for the PKCE
verifier, SHA-256/S256 for the challenge, permits one pending login, validates
state, consumes the callback once, and clears pending material after success,
failure, expiry, or cancellation. Android preserves these semantics. Access
and refresh tokens are stored only in the Android Keystore encrypted envelope.

### Likes and playlists

| Client function | Method and route | Request | Response | Android mapping |
| --- | --- | --- | --- | --- |
| `likes` | `GET /v1/library/likes` | Bearer auth. | `{song_ids,revision}`. | Replace cloud-like snapshot transactionally; local favorites remain separate. |
| `setLike(..., true)` | `PUT /v1/library/likes/{songId}` | Bearer, no body, idempotency key on queued desktop mutations. | Owned Worker returns `{}`. | Persist optimistic mutation before send; positive canonical song IDs only. |
| `setLike(..., false)` | `DELETE /v1/library/likes/{songId}` | Same. | `{}`. | Same queue/idempotency semantics. |
| `playlists` | `GET /v1/library/playlists` | Bearer. No paging parameters. | Actual owned Worker shape `{playlists:[Playlist]}`. Desktop also tolerated a bare array or `{results}`. | Pin fixtures to `{playlists}`; compatibility decoder may accept legacy shapes but persistence always uses normalized records. |
| `playlist` | `GET /v1/library/playlists/{id}` | Bearer; ID matches the bounded opaque-ID grammar. | Playlist record. | Defined but not invoked by pinned main; use for targeted conflict refresh if implemented. |
| `createPlaylist` | `POST /v1/library/playlists` | Bearer; `{name,description,client_migration_id}`; idempotency key. | Playlist record, normally 201. Repeating a migration ID may return existing record with 200. | Offline queue; stable migration ID for one-time local-to-cloud migration. |
| `updatePlaylist` | `PATCH /v1/library/playlists/{id}` | Bearer; optional `name`, optional `description`, and `base_revision`; idempotency key. | Updated Playlist; 409 `revision_conflict` on stale supplied revision. | On 409, fetch/rebase or ask the user. Never retry unchanged automatically. |
| `deletePlaylist` | `DELETE /v1/library/playlists/{id}` | Bearer, no body, idempotency key. | `{}`. | Tombstone pending state until acknowledged; retain local-only playlists. |
| `setPlaylistSong(..., true)` | `PUT /v1/library/playlists/{id}/songs/{songId}` | Desktop sends no body; Worker accepts optional `{base_revision}`; idempotency key. | Updated Playlist. | Android sends `{base_revision}` and resolves 409 before retry. |
| `setPlaylistSong(..., false)` | `DELETE /v1/library/playlists/{id}/songs/{songId}` | Desktop sends no body; idempotency key. Worker does not parse a DELETE body. | Updated Playlist. | Refetch/reconcile revision after acknowledgement; do not invent a DELETE body dependency. |
| `reorderPlaylist` | `PUT /v1/library/playlists/{id}/order` | `{song_ids:[unique positive IDs],base_revision}`; idempotency key. The list must contain every current playlist song exactly once. | Updated Playlist; 409 on stale revision, 400 on invalid order. | Transactional optimistic order plus explicit conflict recovery. |

The desktop queues at most 2,000 pending cloud mutations and sends at most 50
ready mutations per flush. It uses a unique mutation ID as the idempotency key.
Android must persist a mutation before optimistic UI is presented, preserve
ordering dependencies (especially create before song additions), and remap the
local playlist ID after create acknowledgement.

### Listening events

| Client function | Method and route | Request | Response | Android mapping |
| --- | --- | --- | --- | --- |
| `listeningEvents` | `GET /v1/listening/events?cursor={opaque}` | Bearer; cursor absent on first page. Desktop bounds cursor text to 512 chars. | `{events,next_cursor}`. Owned Worker returns at most 500 events ordered newest first by `(played_at,id)`. | Pull until cursor is empty with cycle detection and a documented safety budget; schedule continuation instead of silently truncating. Merge by event ID set union. |
| `uploadListeningEvents` | `POST /v1/listening/events/batch` | Bearer; `{events:[ListeningEvent]}`, at most 500. | `{acknowledged_event_ids,next_cursor}`. | Keep each event pending until its ID is explicitly acknowledged. Event IDs make re-upload idempotent; an idempotency header is optional defense in depth. |

## Retry, rate-limit, and cancellation policy

### Archive operations

The desktop archive client used four total attempts for retry-enabled
operations. GET/HEAD/OPTIONS were retryable by default on timeout,
`AbortError`/`TimeoutError`, HTTP 429, HTTP 5xx, and network `TypeError`. Delay
was numeric `Retry-After` or body `retry_after` capped at 30 seconds, otherwise
750 ms exponential backoff capped at 10 seconds. POST and DELETE used one
attempt except `cancelZip`, which explicitly enabled retries.

Android uses bounded exponential backoff with jitter only for idempotent reads,
Range reads proven compatible by validators, and semantically idempotent
cancellation. It honors both numeric and HTTP-date `Retry-After`. It does not
automatically retry validation failures, most 4xx responses, ZIP-job creation,
or unsafe mutations. Cancellation interrupts the current call and any backoff
delay immediately and is never converted into a retry.

The desktop cleared raw-response timeouts when headers arrived, so a stalled
body could run forever. Android's transfer layer has separate bounded header,
idle-body, and overall-policy timeouts. User cancellation closes the response
body, stops file growth within five seconds, persists a checkpoint, and moves
through `cancelling` to `cancelled`.

### Account operations

The desktop account transport did not retry automatically. It classified
timeouts, offline/network failures, 409, 429, and 5xx as retryable metadata;
caller cancellation was nonretryable. It parsed `retry_after_seconds`, numeric
`Retry-After`, and HTTP-date `Retry-After`.

Android preserves no-automatic-retry for auth exchange and mutations. Queued
idempotent library changes may retry after their persisted due time. HTTP 409
is a conflict signal, not a transport retry: refetch and rebase first. HTTP 401
on an authenticated operation triggers at most one serialized refresh and one
replay when the operation is safe; invalid refresh clears auth. Cancellation
interrupts calls and queue delays without changing valid local or cached state.

## Required deterministic fixtures

The fixture server must use route-specific handlers and exact origins. At
minimum it provides:

| Fixture ID | Scenario and assertion |
| --- | --- |
| `archive-health-ok` | `/health/` returns `{status:"ok"}`. |
| `archive-catalog-pages` | Two `/songs/` pages with count/next/previous and one overlapping song; normalization deduplicates without losing order. |
| `archive-catalog-legacy-items` | Legacy `{items}` envelope remains decodable but is normalized identically. |
| `archive-song-invalid` | Invalid ID, traversal path, oversized title, unsafe artwork origin, malformed duration, and unknown category are rejected or safely degraded field-by-field. |
| `archive-era-pages` | Multiple era pages, snake/camel aliases, absent next, and a cyclic next/safety-cap case. |
| `archive-list-all-large` | Valid entries plus malformed path; oversized body and excessive item count fail without replacing cache. |
| `archive-json-malformed` | 2xx malformed JSON is a contract error. |
| `archive-json-oversized` | Announced and chunked-over-limit JSON both fail. |
| `archive-slow-headers` | Header timeout is classified and safely retryable for GET. |
| `archive-slow-body` | Idle body timeout closes the body rather than waiting indefinitely. |
| `archive-429` | Numeric and HTTP-date Retry-After are honored and capped; cancellation interrupts wait. |
| `archive-redirect-cross-origin` | Redirect to a different origin is rejected without forwarding headers. |
| `archive-download-200` | Fresh file download streams atomically with content length and validator. |
| `archive-download-range-206` | Compatible `Content-Range` plus same validator resumes at the exact checkpoint. |
| `archive-download-range-200` | Server ignores Range; client safely restarts instead of appending. |
| `archive-download-etag-mismatch` | Changed validator discards/replaces incompatible partial only through the documented safe restart path. |
| `archive-disconnect-after-headers` | Stream fails after headers, checkpoint remains accurate, and retry does not duplicate bytes. |
| `archive-download-cancel-after-headers` | Body closes and partial size stops growing within five seconds. |
| `archive-radio-live` | Full radio payload, safe stream URL, clock fields, queue preview, and fallback stream. |
| `archive-radio-failure` | Status polling failure retains playback/cached metadata where valid. |
| `zip-start-progress-ready` | Start aliases job ID, status advances, final exact URL validates. |
| `zip-invalid-download-url` | Wrong origin/path, credentials, query, fragment, and cleartext are rejected. |
| `zip-server-failure` | Failed/cancelled/error terminal aliases become a durable failed/cancelled job. |
| `zip-cancel` | Local body and disk activity stop while server cancel succeeds, fails, times out, and is repeated. |
| `zip64-valid` | A small deterministic Zip64-format fixture validates and extracts by streaming. |
| `zip-unsafe-entry` | Absolute, dot-dot, drive-letter, symlink, invalid-name, and suspicious-expansion entries are rejected before escaping storage root. |
| `account-auth-success` | Start, validated authorize URL, exact callback, one-use exchange, encrypted session projection. |
| `account-auth-bad-state` | Mismatch, duplicate callback field, reused callback, expired pending attempt, and untrusted authorize origin all fail closed. |
| `account-session-refresh` | Expiring session makes one refresh under concurrent callers and rotates both tokens. |
| `account-refresh-offline` | Cached identity/session is retained; no token is logged. |
| `account-refresh-401` | Invalid refresh clears cloud auth but preserves local library/history. |
| `account-response-oversized` | Announced and streaming response over 2 MiB fail. |
| `account-playlists-envelope` | Actual `{playlists}` shape and full record normalization. |
| `account-mutation-idempotent` | Repeating the same key and payload returns the same result once. |
| `account-idempotency-key-misuse` | Same key on a different operation is detected client-side and never sent. |
| `account-playlist-conflict` | 409 causes refetch/rebase UI state, not unchanged automatic retry. |
| `account-listening-pages` | More than one 500-event page, cursor cycle, and continuation beyond 20 pages without silent loss. |
| `account-listening-partial-ack` | Only returned acknowledged IDs leave the pending journal. Missing ack field is an error. |
| `account-logout-offline` | Local encrypted session clears even when revocation fails; local favorites/playlists remain. |

Fixtures must use synthetic IDs, tokens, paths, and events. Never record live
account responses or user library data in the repository.

## Discovered desktop shortcomings and deliberate Android differences

1. `docs/account-api-contract.md` says no staging/live origin is configured and
   an absent environment variable leaves the app unconfigured. The pinned
   `electron/main.js` instead hard-codes the staging Worker as a default, while
   `api/README.md` also says staging configuration exists. Android build
   configuration and documentation are authoritative and must agree; no silent
   runtime fallback is permitted.
2. The account contract mentions cover uploads capped at 250 KiB, and playlist
   records expose `cover_url`, but neither the client nor owned Worker defines a
   cover-upload route. Worker playlist create/update ignores cover fields.
   Android must not invent this endpoint; cloud cover editing remains deferred
   until an owned contract exists.
3. The owned Worker's likes `revision` is hard-coded to `"1"`; it is not a
   useful change token. Android treats it as opaque metadata and does not use it
   for conflict correctness.
4. The Worker's idempotency lookup is scoped only by `(user_id,key)` and records
   method/route without checking them before replay. Android guarantees unique
   keys and refuses local reuse across operation/payload combinations.
5. The desktop classifies every 409 as retryable and later retries the unchanged
   payload. Android performs conflict recovery first.
6. Desktop computes a playlist `baseRevision` for song add/remove but drops it
   before `setPlaylistSong`. Android includes it on PUT and reconciles the
   returned revision after either method.
7. Desktop accepts playlist-list responses as a bare array, `{playlists}`, or
   `{results}` because the prose contract omitted the envelope. The actual
   Worker returns `{playlists}`; that is the primary Android fixture.
8. Desktop listening pull stops after 20 pages, silently limiting one sync to
   10,000 events with the current 500-event server page. Android checkpoints
   and schedules continuation until completion.
9. Desktop treats all submitted listening events as acknowledged if the
   response omits `acknowledged_event_ids`. Android requires explicit IDs.
10. Desktop archive JSON is unbounded and `/files/list-all/` is unpaged. Android
    bounds bytes and item counts and preserves prior valid cache on overflow.
11. Desktop archive fetch follows redirects, and its generic request accepts an
    arbitrary absolute URL. Android has typed exact-origin routes and rejects
    unapproved redirects.
12. Desktop raw-response timeout ends after headers. Android enforces stream
    idle/cancellation behavior after headers.
13. Desktop individual resume trusts only status 206 and expected final size,
    without ETag/Last-Modified validation. Android resumes only with compatible
    `Content-Range` and a stable validator.
14. Desktop full-collection download deletes its partial at every start and is
    explicitly non-resumable. Android checkpoints and resumes when validators
    prove compatibility.
15. Desktop server cancel is fire-and-forget and ignores failure. Android still
    prioritizes immediate local cancellation, but records remote-cancel outcome
    and retries only the semantically idempotent cancel within a bounded policy.
16. Desktop individual downloads can multiply four inner HTTP attempts by four
    outer stream attempts. Android uses one coordinated retry budget per
    operation.
17. Several legacy client bindings are not active features. Android records
    them as compatibility-only instead of presenting unverified or generic UI.
