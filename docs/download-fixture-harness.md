# Deterministic download fixture harness

Run the device-independent transfer lane from the repository root:

```powershell
.\scripts\verify-download-fixtures.ps1
```

With exactly one authorized Android device attached, include app-specific filesystem instrumentation:

```powershell
.\scripts\verify-download-fixtures.ps1 -Device
```

The JVM integration fixture binds only to an ephemeral IPv4 loopback port and is stopped after
each test. It exposes deterministic endpoints for a stable ETag/Range file, changed ETag resume,
disconnect immediately after headers, a slow response body, HTTP 429, collection preparation
progress, collection cancellation, and a small collection ZIP. The tests assert exact output bytes,
safe restart behavior, typed rate-limit failure, cancellation byte-stop, and streamed extraction.

The broader download suite also proves Zip64 recognition, traversal/symlink/collision rejection,
CRC verification before checkpoint skipping, publication failure preservation, and coordinator
recovery after simulated process recreation. The connected test repeats app-specific seek/resume,
replacement rollback, and collection extraction against the device filesystem.

Storage Access Framework picker and permission-revocation journeys use an interactive picker plus a
debug-only permission probe because Android does not provide a reliable cross-provider automation
contract. The final physical-device gate completed this sequence:

1. Select an empty document-tree fixture directory and grant persistent access.
2. Replace an existing fixture file, revoke the directory permission while a second replacement is
   pending, and verify the prior final remains readable and Retry requests access again.
3. Regrant the same tree, retry, and verify the temporary document is removed only after the final
   is published.
4. Interrupt the fixture collection after response headers and confirm its byte count remains
   unchanged for at least five seconds after Cancel.

All four checks passed on the Samsung SM-S948U. The app now validates that the selected tree still
exists in `persistedUriPermissions` before each SAF operation, so providers that surface a revoked
tree as a generic missing-document error still produce the typed permission-loss recovery path.
The connected slow-socket test observed unchanged partial length after 5.2 seconds. Evidence is in
`qa/artifacts/20260805-200308/device/saf-revocation-summary.md` and the connected test report.

Do not use personal media or clear application data for this lane.
