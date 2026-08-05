# ADR 0002: App storage plus SAF

Status: accepted

Use app-specific external storage by default and an optional persisted SAF document tree. Do not request broad storage management. `VaultStorage` exposes display names and document identity rather than invented filesystem paths and validates every untrusted archive segment.

