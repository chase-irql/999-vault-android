# ADR 0004: MediaSessionService is playback authority

Status: accepted

One process service owns one ExoPlayer and MediaSession. UI components use a MediaController facade and never instantiate a player. Queue policy is a pure state machine; service state is projected durably for user-initiated recovery.

