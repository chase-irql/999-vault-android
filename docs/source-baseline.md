# Desktop source baseline

- Source path: `C:\Users\user\Documents\jw-song-downloader`
- Remote: `https://github.com/choz-dev/juicewrld-api-vault.git`
- Required and inspected commit: `3b24ac807c93c3030cacd814ae605d30b90b2799`
- Inspection date: 2026-08-05
- Working tree note: the Android port prompt was untracked; tracked source matched the commit.

## Checks run

- `npm run check`: passed.
- `npm test`: 65 passed, 0 failed.
- `npm run api:check`: passed.
- `npm run api:test`: 2 passed, 0 failed.
- Electron smoke and live ZIP diagnostics were not run during the baseline pass because they require interactive/runtime or external service state.

## Sources consulted

`README.md`, `api-client.js`, `download-manager.js`, `electron/main.js`, `electron/preload.cjs`, `electron/account-api.js`, auth/catalog/cloud/listening/library/download modules, renderer models/application, QA tests, ZIP diagnostic client, and documents under `docs/`.

## Discovered mismatch

`docs/account-api-contract.md` says no live/staging account origin is configured, while `electron/main.js` at the same commit defines `https://vault999-account-staging.2lt.workers.dev` as its default. Android treats the owned account origin as explicit build configuration until the owner confirms the deployment; signed-out behavior is unaffected.

Additional source/document differences found during contract inventory:

- `docs/data-migrations.md` names `secure-session.json`; `electron/main.js` actually writes `account-session.json`.
- The migration document describes a mappable/unmappable preview and linked cloud labeling; the renderer instead filters to trusted canonical IDs, queues eligible playlists directly, and reports a playlist count.
- `docs/architecture.md` describes callback consumption as account IPC, but the renderer bridge intentionally does not expose it; the privileged Electron `open-url`/single-instance lifecycle consumes it.
- README media wording names container formats, but Chromium codec availability still decides playback and the UI supplies an unsupported-format recovery path.
- README calls cover export embedded-art extraction, while the implementation uses the JuiceWRLDAPI cover endpoint.
- Historical readiness documents list 19 and 49 tests; the fresh baseline run executed 65 desktop tests plus 2 Worker tests.
- Playlist item mutations persist a base revision locally but the current account client drops it for add/remove calls; desktop conflict retries therefore do not perform a safe refetch/rebase.
- Individual download resume validates only HTTP 206 and final size, not an ETag/Last-Modified validator; collection ZIP download restarts rather than resuming. Android intentionally strengthens both contracts.
