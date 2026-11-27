# Stream Buddy

An Android privacy-aware media library, cache-first player, offline downloader, and BitTorrent streamer.

Stream Buddy lets you organize titles you own or are authorised to stream, attach your own magnet link, `.torrent` file, HTTP(S)/HLS/DASH link, or local media file, and play or cache them locally behind a fail-closed VPN gate.

```
Library / Browser / Intent ──▶ Source attached (encrypted in Room)
                                  ├──▶ HTTP / HLS / DASH ──▶ OkHttp + NetworkGuard ──▶ PlaybackCache / Offline Downloads
                                  └──▶ Magnet / .torrent  ──▶ LibtorrentEngine (libtorrent4j) ──▶ LoopbackServer (127.0.0.1)
                                                                                                      ▼
                                                                                     PlayerEngine (Media3 ExoPlayer)
```

## Architecture & active modules

| Area | Implementation |
| --- | --- |
| **Navigation shell** | `MainActivity` + `ui/App.kt` (`StreamBuddyRoot`) with five tabs: **Library** (including a **My Library / Latest · TMDB** switcher with `DiscoverScreen` and `CatalogDetailScreen`), **Browser**, **Apps**, **Storage**, **Settings**, plus detail, add-title (TMDB search, custom title, `.torrent` import & creation), player views, and an active mini-player bar. Includes Android TV Leanback launcher/banner support (`tv_banner.xml`), deep links (`streambuddy://queue`, `library`, `discover`, `latest`, `browser`, `apps`, `storage`, `player`, `settings`), `magnet:` links, `video/*`, `audio/*`, and `application/x-bittorrent` `ACTION_VIEW` intents, and `ACTION_SEND` text shares. |
| **Encrypted persistence** | `AppCrypto` / `SecurePreferences` (Android Keystore AES-256-GCM) + `SettingsStore` (`stream_buddy_settings`) + Room v4 `AppDb` (`SecretConverters` encrypts user titles/URLs/session names). `android:allowBackup="false"` prevents device-bound ciphertext backups. |
| **VPN gate & networking** | `VpnMonitor` tracks the app's active network + VPN capabilities (`registerNetworkCallback` + `registerDefaultNetworkCallback`). `NetworkPolicy` / `NetworkGuard` enforce fail-closed blocking across `Http.client`, `Http.mediaClient`, `TmdbClient`, `MotnClient`, `PosterLoader`, `PageLinkScraper`, `BrowserDownloads`, `Downloads`, `PreCacheEngine`, `PreCacheWorker`, `SmartPrefetchWorker`, `PlayerEngine`, and `TorrentManager`. |
| **Playback & background session** | `PlayerEngine` owns `ExoPlayer`, `MediaSession` (bound via `MediaController` to `PlaybackService` for foreground notifications, media-button dispatch, and background playback), `TunableLoadControl` (dynamic min/max buffer bounds), `PlaybackCache` (`DynamicLruEvictor`, configurable budget), and `PlaybackAnalytics`. `PlayerScreen` provides seek scrubbing, `0.5x–2.0x` playback speed, audio & subtitle track selection (with preferred-language auto-selection), multi-file torrent episode/quality switching, live stats, and an optional 5-second next-episode/file autoplay countdown. |
| **Adaptive optimizer & smart pre-cache** | `OptimizerRuntime` + `OptimizerCore` score stall risk (`RULES` baseline or on-device `LogisticStallModel`) and tune buffer targets / adaptive bitrate caps. `LibraryPreCacher` + `SmartPrefetch` + `SmartPrefetchWorker` train on recorded watch history via `PrefetchPredictor`, rank Library HTTP/HLS sources, and pre-cache segments directly into `PlaybackCache` via `PreCacheEngine`. |
| **Offline downloads** | `Downloads` + `SbDownloadService` keep offline HLS/DASH/MP4 downloads in a separate `NoOpCacheEvictor` `SimpleCache` routed through `OkHttpDataSource` + `Http.mediaClient` (`NetworkGuard` + HTTPS downgrade protection), with per-item Pause, Resume, Retry, Remove, Wi-Fi-only gating, and automatic pause on VPN drop. |
| **BitTorrent streaming & creation** | `TorrentManager` + `LibtorrentEngine` (`libtorrent4j`, forced peer encryption, LSD disabled) + `TorrentFilePolicy` (safe path canonicalization, quality-aware main file choice, multi-file selection, `Priority.IGNORE` on disallowed files) + `TorrentPieceWindow` + `LoopbackServer` (loopback `127.0.0.1` with 256-bit constant-time route token and verified-range blocking). Supports `.torrent` import and `.torrent` creation from local videos into persistent `torrents/meta/` storage. |
| **Malware & quarantine** | `SecurityCenter`, `MalwareScanner`, and `QuarantineStore` inspect downloaded/torrent/browser files and isolate threats into an app-private non-executable quarantine directory before playback. |
| **In-app browser, apps & metadata** | `BrowserScreen`, `PageLinkScraper`, `BrowserDownloads`, `AppsScreen` (launcher for installed streaming apps), `DiscoverRepository` (`In cinemas`, `On the air`, `Upcoming`, `Trending`), `TorrentIndexClient` (on-demand YTS/EZTV magnet lookup with redirect protection and pinned hosts), `TorrentDownloadManager` (VPN-gated offline magnet downloader), `TmdbClient` (redirect-disabled, pinned host), `MotnClient` (redirect-disabled, pinned host), and `PosterLoader` (`image.tmdb.org` only, bounded streaming). |
| **Standalone Python utility** | `tools/find_playable_links.py` extracts playable links from HTML documents (`python3 tools/find_playable_links.py --self-test`). |

## Honest limitations

* **VPN enforcement is app-level.** `VpnMonitor` verifies that Android's active route for Stream Buddy is a `TRANSPORT_VPN` network before sending requests or starting/continuing torrent traffic; the user supplies the device VPN.
* **DRM services are not cached or decrypted.** Widevine/FairPlay/PlayReady streams cannot be pre-cached or stripped; the **Apps** tab opens installed streaming apps directly.
* **Legacy queue modules.** Earlier `QueueScreen`, `MainViewModel`, `StreamRepository`, `TorrentEngine` (`jlibtorrent`), and `PreCacheWorker` remain in source for compatibility with `SmokeTest` and existing unit tests while active UI flows use `LibraryRepository`, `LibraryPreCacher`, `PlaybackCache`, and `TorrentManager` (`libtorrent4j`).

## Build & CI

Requirements: JDK 17, Android SDK Platform 35 + Build-Tools 35 (`compileSdk = 35`, `targetSdk = 35`, `minSdk = 26`).

```bash
# Optional local debug API keys (never committed):
cp .env.example .env

# Point Gradle at your Android SDK:
echo "sdk.dir=$HOME/Android/Sdk" > local.properties

# Run pure-JVM unit tests:
./gradlew testDebugUnitTest

# Run lint and assemble debug APK:
./gradlew lintDebug assembleDebug

# Stage a signed release APK into dist/ (requires RELEASE_STORE_* for non-debug signing):
./gradlew :app:verifyReleaseSigned
```

GitHub Actions workflows:

* `.github/workflows/android.yml` — runs `testDebugUnitTest`, `lintDebug`, `assembleDebugAndroidTest`, `assembleDebug`, and `assembleRelease bundleRelease` on pushes/PRs to `main` (and optional emulator `SmokeTest` on dispatch).
* `.github/workflows/android-debug.yml` — builds and uploads `dist/stream-buddy-debug-v*.apk` (`stream-buddy-debug-apk`).
* `.github/workflows/android-release.yml` — runs on `v*` tags or dispatch, verifies release signing (`CN=Android Debug` is rejected on tagged releases), and attaches the APK to GitHub Releases.

## Tests

Pure-JVM unit tests live in `app/src/test/java/com/streambuddy/app/`:

* Security & cryptography: `AesGcmCipherTest`, `MalwareScannerTest`, `SettingsRepositoryTest`
* Parsing, filtering & torrent safety: `MagnetTest`, `MagnetLinkTest`, `HttpRangeTest`, `TorrentFilePolicyTest`, `TorrentPieceWindowTest`, `LoopbackServerTest`
* Streaming, routing & browser: `PageLinkScraperTest`, `HlsPlaylistParserTest`, `DrmDetectorTest`, `PrefetchPlannerTest`, `StreamUrlTest`, `StreamKindTest`, `SharedLinkTest`
* Optimizer, settings & metadata: `AppSettingsTest`, `LibraryFiltersTest`, `OptimizerCoreTest`, `TmdbImagesTest`, `PrefetchPredictorTest`, `SeriesMatcherTest`, `FormatTest`, `FmtTest`

Instrumented smoke tests live in `app/src/androidTest/java/com/streambuddy/app/SmokeTest.kt`.
