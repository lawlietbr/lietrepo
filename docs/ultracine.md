# UltraCineTV — Abyss and Byse

UltraCineTV provides films, series, search, seasons and episodes from `https://www.ultracinetv.lat/`. It resolves the published panel's Abyss and Byse sources while preserving their server/audio labels.

## Playback

- **Byse:** solves the published challenge, decrypts the versioned AES-GCM playback response and passes the original HLS master to the player. Trick-play iframe playlists are not exposed as video sources.
- **Abyss multipart:** decrypts the initial 64 KiB and maps byte ranges across the published remote files through a bounded, loopback-only adapter. The adapter supports seeking and concurrent requests, expires old registrations and closes on plugin unload.
- **Abyss legacy:** generates the native `/sora/` stream URL with the numeric-key derivation used by the published player. A zero multipart size is treated as a single file.

The multipart Abyss adapter runs on the playback device; Abyss is excluded from casting. Vidsrc is not part of this implementation. No authorization tokens or signed media URLs are committed.

## Validation

The installed CloudStream 4.9.1 application on MuMu Android 12 passed nine instrumented playback cases, with video frames, decoded audio and progressing positions before and after seeking:

- Film: Abyss 480p, 720p and 1080p; Byse 1080p.
- Episodes: two legacy Abyss episodes, one current Abyss episode and two Byse episodes at 1080p.
- The 480p film seek reached 1h48m20s, crossing the first 512 MiB storage part.

Catalog checks returned 24 home items, 14 search results and 22 episodes across three seasons. Seventeen automated checks passed, including a live cross-part decode through the Kotlin adapter. These are bounded playback/seek tests, not full-length viewing of every title.

Sanitized results: [Android validation report](validation/2026-11-19-ultracine-android.json).

Run unit checks with `gradlew.bat :UltraCineTV:testDebugUnitTest`. The live JVM check is opt-in with `ULTRA_LIVE=1`, `ULTRA_ABYSS_MEDIA` pointing to locally obtained metadata, and FFmpeg on PATH. The opt-in Android suite is built with `gradlew.bat :UltraCineTV:make -PultraValidation=true` and runs on plugin load on a test device. Normal builds exclude the test runner and automatic playback tests.

NetCine remains outside this change.
