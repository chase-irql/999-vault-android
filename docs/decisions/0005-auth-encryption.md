# ADR 0005: Keystore session envelope

Status: accepted

Opaque account tokens are encrypted with a non-exportable Android Keystore AES-256-GCM key. A versioned nonce/ciphertext envelope is atomically rotated in app-private storage. Room, DataStore, logs, saved instance state, screenshots, and backups never contain tokens.

