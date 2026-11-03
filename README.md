# Stream Buddy

An Android pre-play cache and cache-first player for HLS and direct media streams.

Stream Buddy does one thing: it downloads the first part of a stream **before** you press play, so
playback starts from local storage instead of the network — and it keeps caching as you watch.

```
Queue a stream  ──▶  WorkManager job  ──▶  Media3 SimpleCache (2 GB LRU)
                                                    ▲
Player tab      ──▶  ExoPlayer, cache-first  ───────┘
```

## Status

CI (`.github/workflows/android.yml`) compiles the app, runs the unit tests and packages a debug
APK on every pull request; the APK is uploaded as the `stream-buddy-debug-apk` artifact of the run.
It has **not** been exercised on a physical device yet, so playback edge cases are untested — see
the limitations below.

## What works

| Area | Behaviour |
| --- | --- |
| **Queue** | Add an HLS playlist (`.m3u8`), a DASH manifest (`.mpd`) or a media file (`.mp4`, `.mkv`, …). Title is optional and derived from the URL when blank. Live cache state, progress, errors, retry and delete. |
| **Pre-caching** | HLS: resolves the master playlist to a variant, then caches segments covering the configured pre-play window (2/5/10/20 min), including `#EXT-X-MAP` init segments and `#EXT-X-BYTERANGE` sub-ranges. Progressive files: caches the first 25 % of the file (8 MB – 150 MB). Runs in the background with a low-priority progress notification and resumes after retries or cancellation. |
| **DRM** | FairPlay, Widevine, PlayReady and CENC are detected from the playlist/manifest and never cached. Ordinary HLS `AES-128` encryption is *not* DRM and stays cacheable. |
| **Playback** | ExoPlayer reads through the same cache, so pre-cached data is used immediately and everything else is cached as it plays (HLS segments always; progressive files when the server reports a length). HLS/DASH manifests deliberately bypass the cache so live playlists never go stale. |
| **Apps** | Lists launchable apps with streaming services first; tapping one opens it. DRM services only play inside their own app, so Stream Buddy launches them instead of pretending it can cache them. |
| **Smart pre-cache** | An on-device model ranks the queue, predicts which stream you will open next (series + episode order, recency, how often you finish, time of day) and pre-caches the best guess deeply and two runner-ups shallowly — on Wi-Fi, while charging. Every prediction shows the reasons behind it and can be switched off. |
| **Torrents** | Paste a `magnet:` link (or share one, or open one from a browser) and the app resolves the metadata over BitTorrent, picks the file that suits this device and connection, and plays it while it downloads — no separate client, no manual file picking. |
| **Settings** | Smart pre-cache on/off + model status, pre-play window, cache quality (lowest/highest HLS variant), cache usage and clearing, notification permission, version/about. |

## Honest limitations

* **DRM streaming services** (Netflix, Disney+, Prime Video, Max, …) cannot be pre-cached. Their
  apps are launch-only.
* **DASH** (`.mpd`) playback is supported; *pre-caching* MPDs is not implemented yet (the queue
  reports "Playback only" for those URLs).
* **Live HLS** is cached best-effort from the newest segments of the playlist snapshot. There is
  no DVR/timeshift support.
* **No background audio**: playback pauses when the app is backgrounded, because no
  `MediaSessionService`/notification transport controls are published yet.
* Cache eviction is LRU inside a fixed 2 GB budget (`CacheManager.MAX_BYTES`); deleting a queue
  entry does not evict its cached segments directly — "Clear cache" in Settings does.
* **BitTorrent is a general-purpose client.** Stream Buddy downloads exactly what a magnet points
  at and applies no source filtering. Use it for content you have the right to access; that is your
  responsibility, not the app's.
* **Only arm64 devices and x86_64 emulators are packaged** (`abiFilters`), because libtorrent ships
  a native library per ABI and the 32-bit builds double the download size.
* **Torrent files are kept** in app-specific external storage (`Android/data/…/Movies/torrents`)
  when a queue entry is removed; delete them from the device to reclaim space, or remove the app.

## What the "AI" actually is

No cloud, no LLM, no data leaving the phone. Pre-play caching needs a decision in milliseconds,
offline, on a device with no reliable GPU access — so the model is a nine-feature logistic
regressor (`ai/PrefetchPredictor.kt`, ~350 lines) that starts from hand-tuned priors and refits
itself from the playbacks recorded on this device:

| Feature | What it captures |
| --- | --- |
| `NEXT_EPISODE` | the queued stream is SxxEyy right after what you last watched |
| `SAME_SERIES` | same show/film as your last session |
| `RECENCY` | how long ago this series was last played (72 h half-life) |
| `FREQUENCY` | how often you come back to it |
| `COMPLETION` | whether you usually finish it (abandoned titles rank lower) |
| `TIME_OF_DAY`, `WEEKDAY` | circular statistics over when you normally watch it |
| `QUEUE_AGE` | how recently you added it (14-day half-life) |
| `BIAS` | baseline appetite for anything new |

Training is 40 SGD epochs with L2, fitted on a background thread after each finished session, using
only events that preceded each sample (no peeking at the future). Fewer than 8 recorded playbacks
and the weights stay at their priors, which the Settings screen states plainly ("Learning" vs
"Personalised"). Probabilities are temperature-scaled (T = 1.7) so a nine-input model never claims
99 % certainty.

The prediction changes the *plan*, not the cache limit: the top pick gets
`base × (0.5 + probability)` clamped to 2–20 minutes, two runner-ups get a 2-minute warm start so a
wrong guess wastes very little, and the periodic pass only runs on an unmetered network while
charging (`SmartPrefetchWorker`). With smart pre-cache off, the app behaves exactly as before:
everything you queue is cached with your configured window.

## Torrents: magnet in, video out

The torrent path exists because "add a magnet link and watch it" is a real, testable feature, and
because BitTorrent has a well-defined answer for every "how does it stream?" question. The pieces:

| Piece | What it does |
| --- | --- |
| `torrent/MagnetLink.kt` | Parses `magnet:?xt=urn:btih:…` — hex or base32 info hash, `dn`, `tr`, `xl`. Pure Kotlin, unit-tested, used by the queue, the share sheet and the `magnet:` VIEW intent. |
| `torrent/TorrentEngine.kt` | One libtorrent session per process. Adds the torrent, waits for BEP 9 metadata (45 s), asks `QualitySelector` which file to play, then prioritises **only that file** (`Priority.NORMAL`, everything else `IGNORE`) so a 40 GB season pack does not download 40 GB. |
| `torrent/QualitySelector.kt` | The "optimal quality" decision: a transparent score over the device's real screen size, MediaCodec decoder limits, HEVC/HDR support, free storage, metered/slow network, container and remux size. Returns the pick *and* the reasons behind it. |
| `torrent/TorrentDataSource.kt` | A Media3 `DataSource` for `streambuddy-torrent://<infohash>/<fileIndex>`. Each `read()` blocks on `havePiece()` for the pieces it needs, with piece deadlines ~48 pieces (≈60 s of readahead) ahead of the playhead. Seeking works because seeking is just another `open()` at a new offset. |
| `cache/PlaybackDataSource.kt` | Routes the torrent scheme to that source, manifests to plain HTTP, and everything else through the Media3 cache. |

**Where the "AI" is, and is not.** There is no LLM here and no cloud model. The quality choice is a
deterministic scoring function with explainable reasons — the same algorithm a good human would
apply, written down. That is the honest version of "AI-powered resolver": the hard part of torrent
playback is *deciding* (which file, when to start, what to drop when the connection sags) and doing
it instantly on-device. The prediction model in `ai/` is separately real, but it rank-orders the
queue; it does not pick torrent files.

**Why playback starts before the download finishes.** libtorrent writes pieces in the order the
player asks for them: `TorrentDataSource` sets a piece deadline for the window being read plus a
readahead, and blocks that read until the pieces land. The first frame therefore waits for the first
handful of pieces, not the whole file. A stall (no bytes for 45 s) surfaces as a playback error
instead of an infinite spinner.

**The pick is a suggestion, not a verdict.** The player shows the files the selector considered as
quality chips ("1080p", "720p", …); tapping one re-prioritises libtorrent onto that file and plays
it. Nothing is hidden behind the score.

**Metered connections.** The selector down-ranks files over 2 GB and flags the reason. The engine
still fetches only the chosen file, so a magnet costs what the chosen file costs — not the torrent's
total size.

## Build

Requirements: JDK 17, Android SDK Platform 35 + Build-Tools 35
(`compileSdk = 35`, `targetSdk = 35`, `minSdk = 26` — libtorrent's native library sets the floor).

```bash
# 1. Point Gradle at your SDK (Android Studio does this for you).
echo "sdk.dir=$HOME/Android/Sdk" > local.properties   # or export ANDROID_HOME=...

# 2. Build the debug APK.
./gradlew assembleDebug

# 3. Run the unit tests (pure-JVM, no device needed).
./gradlew testDebugUnitTest
```

The APK lands in `app/build/outputs/apk/debug/app-debug.apk`. CI runs the same two commands on
every pull request and uploads the APK as the `stream-buddy-debug-apk` artifact.

## Project layout

```
app/src/main/java/com/streambuddy/app/
├── MainActivity.kt              bottom-nav shell (apps / queue / player / settings)
├── cache/
│   ├── CacheManager.kt          the single Media3 SimpleCache + data source factories
│   ├── PlaybackDataSource.kt    cache-first for media, direct for manifests
│   ├── SmartPrefetch.kt         ranks the queue, records sessions, refits the model
│   ├── SmartPrefetchScheduler.kt periodic prediction pass (unmetered + charging)
│   ├── AppScope.kt              process scope for writes that outlive a view model
│   └── PreCacheWorker.kt        WorkManager job: progress → Room + notification
├── data/
│   ├── AppDb.kt                 Room entities/DAOs for the queue and watch history
│   ├── WatchHistory.kt          recorded playback sessions (the training data)
│   ├── PredictorRepository.kt   persisted model weights
│   └── SettingsRepository.kt    SharedPreferences-backed settings as a StateFlow
├── ai/                          on-device prediction (pure Kotlin, unit-tested)
│   ├── Models.kt                watch events, candidates, predictions
│   ├── SeriesMatcher.kt         "Show S02E05" / "show.2x05" → series + episode
│   ├── PrefetchPredictor.kt     features, logistic regression, SGD training
│   └── PrefetchPlan.kt          prediction → how many minutes to cache
├── net/Http.kt                  shared OkHttp client
├── stream/
│   ├── HlsPlaylistParser.kt     RFC 8216 subset: variants, segments, byte ranges, maps
│   ├── HlsPlaylist.kt           parsed models
│   ├── HlsAttributes.kt         quote-aware attribute-list parser
│   ├── DrmDetector.kt           FairPlay/Widevine/PlayReady/CENC detection
│   ├── PrefetchPlanner.kt       which segments cover the pre-play window
│   ├── PreCacheEngine.kt        segment download loop into the Media3 cache
│   ├── StreamFetcher.kt         playlist/probe HTTP calls
│   ├── StreamKind.kt            URL/content-type → stream type
│   ├── StreamUrl.kt             URL validation, scheme repair, title suggestion
│   └── PreCacheErrors.kt        retryable vs. permanent failures
├── ui/
│   ├── MainViewModel.kt         queue/settings flows + the single ExoPlayer
│   ├── AppsScreen.kt            launchable apps, streaming services first
│   ├── QueueScreen.kt           add streams, per-entry cache state
│   ├── PlayerScreen.kt          PlayerView + queue shortcuts
│   ├── SettingsScreen.kt        pre-play window, quality, storage, notifications
│   ├── Components.kt            shared Compose pieces
│   └── Theme.kt                 dark Material 3 theme
└── util/Format.kt               byte/duration/percent formatting
```

Everything in `stream/` (except `PreCacheEngine`/`StreamFetcher`), `ai/` and `util/` is plain JVM
Kotlin with no Android dependencies, which is what `app/src/test` exercises — including the
predictor and its training loop.

## Tests

`./gradlew testDebugUnitTest` runs 112 unit tests covering:

* master/media playlist parsing, `#EXT-X-BYTERANGE`, `#EXT-X-MAP`, BOM/CRLF handling,
  relative/protocol-relative/awkward URL resolution,
* DRM detection (including "AES-128 is not DRM"),
* pre-play segment planning for VOD and live,
* URL validation and stream-kind detection,
* series/episode parsing (`S02E05`, `2x05`, `Episode 5`, movies as their own series) and
  "is this the next episode" logic,
* the predictor: ranking, explanations, cold start, training convergence, that the untrainable
  `QUEUE_AGE` weight is left at its prior, temperature-bounded probabilities,
* prefetch depth planning (top pick vs. warm starts vs. nothing at all),
* magnet parsing (hex/base32 info hashes, `dn`/`tr`/`xl`, rejecting non-magnets),
* torrent file classification (video vs. sample/trailer, `1080p`/`4K`/`2160p` tags, HEVC/HDR/remux),
* quality selection (screen-vs-file height, decoder and storage limits, metered and slow links,
  ordering, downgrade),
* formatting helpers.

## Design notes

* **One cache instance per process.** Media3 requires a single `SimpleCache` per directory, so
  `CacheManager` is a singleton used by both playback and pre-caching. That is what makes
  pre-cached segments show up instantly in the player.
* **Cache keys line up.** Media3's default cache key is the `DataSpec` key, falling back to the
  resolved URI string. Pre-caching builds the same `DataSpec` ExoPlayer's HLS chunk source builds
  (`new DataSpec(uri, byteRangeOffset, byteRangeLength)`), so both sides write and read the same
  key, position and length — playback finds the pre-cached blocks.
* **Manifests bypass the cache.** HLS reloads its media playlist every target duration; caching
  those responses would serve a stale playlist and stall live playback.
* **No foreground service.** WorkManager runs the pre-cache job with a network constraint; it is
  resumable, so a stopped job simply continues where it left off on retry.
* **The model never decides *what* you may watch** — it only decides what is worth downloading
  early. It is trained on this device's history, stored in SharedPreferences as nine doubles, and
  reset from Settings. With no history it degrades to fixed priorities rather than guessing wildly.

## Roadmap

* DASH pre-caching (MPD parsing + segment templates).
* `MediaSessionService` for background audio and lock-screen controls.
* Per-stream cache eviction, configurable cache budget.
* HLS variant switching during pre-cache (currently lowest or highest only).
* Train on more signals (cast history, time-to-first-play) and calibrate the probabilities against
  real outcomes once there is usage data; a neural model is not worth it at this input size.
* Torrents: trackers-less DHT-only magnets work but are slower to start; a "play the next file"
  option for season packs, subtitles from the same torrent, and a per-device bitrate probe so the
  selector can use measured throughput instead of a size heuristic.
* Let the learned model rank *torrent files* too, once there is data on which picks people finish.
