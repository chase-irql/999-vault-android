# Dependency policy and notices

Every build and runtime artifact is pinned in `gradle/libs.versions.toml`. Dynamic versions, Git coordinates, local AARs, and repositories outside Google Maven, Maven Central, and the Gradle Plugin Portal are rejected by policy.

## Approved runtime dependencies

- AndroidX / Jetpack Compose / Material 3 / Room / DataStore / Media3 / WorkManager / Browser / Benchmark: official Android libraries under their published Apache 2.0 notices. They provide platform integration that should not be custom-reimplemented.
- Kotlin standard library, coroutines, and serialization: JetBrains-maintained Apache 2.0 libraries used for structured concurrency and bounded DTO parsing.
- OkHttp: maintained Square HTTP transport, Apache 2.0; used for streaming, cancellation, Range headers, redirect control, and deterministic timeouts.
- Coil Compose: maintained image loader, Apache 2.0; used for bounded cover decoding and cache policy without shipping a bespoke decoder/cache.

No additional archive/ZIP library is accepted at this point. The platform streaming ZIP implementation must pass the Zip64 fixture gate; if it cannot, a dependency decision must document license, maintenance, security posture, and size before adding a pinned alternative.

`scripts/verify.ps1` generates dependency-tree and SBOM-style coordinate reports under ignored QA artifacts. Release notices are generated only from resolved release runtime dependencies; no claim is made for content streamed from the archive.

