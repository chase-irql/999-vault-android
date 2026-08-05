# ADR 0003: Transfer scheduler split

Status: accepted

User-started transfers use API 34+ user-initiated data-transfer jobs and an API 26-33 foreground CoroutineWorker fallback. Both observe one Room state machine and the same cancellation token, socket, checkpoint, and notification actions.

