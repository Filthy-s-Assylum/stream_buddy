package com.streambuddy.app.security

import android.content.Context
import androidx.media3.exoplayer.offline.Download
import com.streambuddy.app.data.SettingsStore
import com.streambuddy.app.download.Downloads
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

data class SecurityEvent(
    val at: Long,
    val fileName: String,
    val verdict: ScanVerdict,
    val reason: String,
    val quarantined: Boolean,
)

data class SecurityUiState(
    val scanEnabled: Boolean = true,
    val scanning: Boolean = false,
    val filesScanned: Int = 0,
    val clean: Int = 0,
    val suspicious: Int = 0,
    val threats: Int = 0,
    val quarantinedCount: Int = 0,
    val quarantineBytes: Long = 0L,
    val lastScanAt: Long? = null,
    val lastError: String? = null,
    val events: List<SecurityEvent> = emptyList(),
) {
    val hasThreats: Boolean get() = threats > 0 || quarantinedCount > 0
}

/**
 * Coordinates the signature scanner over everything Stream Buddy stores:
 * torrent pieces, offline downloads and files captured by the built-in
 * browser. A malicious file is moved into app-private quarantine before it
 * can be played or exported.
 */
class SecurityCenter(
    private val context: Context,
    private val settingsStore: SettingsStore,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()
    private val store = QuarantineStore(context)
    private val scanned = HashMap<String, String>()
    private var completedDownloadIds: Set<String> = emptySet()

    private val _state = MutableStateFlow(
        SecurityUiState(scanEnabled = settingsStore.current().malwareScanEnabled),
    )
    val state: StateFlow<SecurityUiState> = _state.asStateFlow()

    private val torrentRoot: File get() = File(context.filesDir, "torrents")
    private val downloadRoot: File get() = File(context.filesDir, "downloads")
    private val browserRoot: File get() = File(context.filesDir, "browser")

    init {
        refreshQuarantine()
        scope.launch { observeCompletedDownloads() }
    }

    fun onSettingsChanged() {
        _state.update { it.copy(scanEnabled = settingsStore.current().malwareScanEnabled) }
    }

    fun scanNow(skip: Set<File> = emptySet()) {
        if (!settingsStore.current().malwareScanEnabled) return
        scope.launch { scanAll("Manual scan", skip) }
    }

    /**
     * Scans a file before it can be handed to the player. Returns null when
     * scanning is disabled or the file is not readable yet.
     */
    suspend fun scanForPlayback(file: File, label: String? = null): ScanReport? {
        if (!settingsStore.current().malwareScanEnabled) return null
        if (!file.exists() || !file.isFile) return null
        return mutex.withLock {
            withContext(Dispatchers.IO) {
                val report = runCatching { MalwareScanner.scanFile(file) }.getOrElse {
                    _state.update { state ->
                        state.copy(lastError = "Could not scan ${file.name}: ${it.message}")
                    }
                    return@withContext null
                }
                if (report.verdict != ScanVerdict.CLEAN) {
                    record(report, label ?: file.name, quarantined = report.blocked)
                }
                if (report.blocked) {
                    val entry = store.quarantine(file, report)
                    if (entry == null) {
                        _state.update {
                            it.copy(lastError = "Threat found but quarantine failed for ${file.name}")
                        }
                    } else {
                        refreshQuarantine()
                    }
                }
                report
            }
        }
    }

    suspend fun scanAll(reason: String, skip: Set<File> = emptySet()): SecurityUiState {
        if (!settingsStore.current().malwareScanEnabled) {
            _state.update { it.copy(scanEnabled = false) }
            return _state.value
        }
        return mutex.withLock {
            withContext(Dispatchers.IO) {
                _state.update {
                    it.copy(scanning = true, lastError = null, filesScanned = 0, clean = 0, suspicious = 0, threats = 0)
                }
                var clean = 0
                var suspicious = 0
                var threats = 0
                var scannedCount = 0
                val skipCanonical = skip.map { it.canonicalFile }
                try {
                    roots().forEach { root ->
                        if (!root.exists()) return@forEach
                        root.walkTopDown()
                            .onEnter { dir -> dir.canonicalFile !in skipCanonical }
                            .filter { it.isFile && inspectable(it, inDownloads = root == downloadRoot) }
                            .forEach { file ->
                                if (file.canonicalFile in skipCanonical) return@forEach
                                val key = "${file.absolutePath}:${file.length()}:${file.lastModified()}"
                                if (scanned[key] != null) return@forEach
                                val report = runCatching { MalwareScanner.scanFile(file) }.getOrNull()
                                    ?: return@forEach
                                scanned[key] = report.verdict.name
                                scannedCount++
                                when (report.verdict) {
                                    ScanVerdict.CLEAN -> clean++
                                    ScanVerdict.SUSPICIOUS -> {
                                        suspicious++
                                        record(report, file.name, quarantined = false)
                                    }
                                    ScanVerdict.MALICIOUS -> {
                                        threats++
                                        val entry = store.quarantine(file, report)
                                        record(report, file.name, quarantined = entry != null)
                                    }
                                }
                            }
                    }
                    _state.update {
                        it.copy(
                            scanning = false,
                            filesScanned = scannedCount,
                            clean = clean,
                            suspicious = suspicious,
                            threats = threats,
                            lastScanAt = System.currentTimeMillis(),
                            lastError = null,
                        )
                    }
                    refreshQuarantine()
                } catch (throwable: Throwable) {
                    _state.update {
                        it.copy(
                            scanning = false,
                            lastScanAt = System.currentTimeMillis(),
                            lastError = "${reason} failed: ${throwable.message ?: throwable.javaClass.simpleName}",
                        )
                    }
                }
                _state.value
            }
        }
    }

    fun refreshQuarantine() {
        val entries = runCatching { store.entries() }.getOrDefault(emptyList())
        _state.update {
            it.copy(
                quarantinedCount = entries.size,
                quarantineBytes = entries.sumOf { entry -> entry.sizeBytes },
                scanEnabled = settingsStore.current().malwareScanEnabled,
            )
        }
    }

    fun quarantineEntries(): List<QuarantineEntry> = runCatching { store.entries() }.getOrDefault(emptyList())

    fun deleteQuarantined() {
        runCatching { store.deleteAll() }
        refreshQuarantine()
    }

    fun release() {
        scope.coroutineContext[Job]?.cancel()
    }

    // ---------------------------------------------------------------------

    private fun roots(): List<File> = listOf(torrentRoot, downloadRoot, browserRoot)

    private fun inspectable(file: File, inDownloads: Boolean): Boolean {
        val name = file.name.lowercase(Locale.US)
        if (name.startsWith(".")) return false
        val extension = name.substringAfterLast('.', "")
        if (extension in SKIP_EXTENSIONS) return false
        // Media3 names cache entries by key, without an extension. Those are
        // media chunks, not executable files; checking them would only produce
        // false positives. Named files are still inspected.
        if (inDownloads && !name.contains('.')) return false
        return true
    }

    private suspend fun observeCompletedDownloads() {
        while (scope.isActive) {
            runCatching {
                val completed = Downloads.snapshot(context)
                    .filter { it.state == Download.STATE_COMPLETED }
                    .map { it.request.id }
                    .toSet()
                if (completed.isNotEmpty() && completed != completedDownloadIds) {
                    completedDownloadIds = completed
                    scanAll("Download completed")
                }
            }
            delay(DOWNLOAD_POLL_MS)
        }
    }

    private fun record(report: ScanReport, label: String, quarantined: Boolean) {
        val event = SecurityEvent(
            at = System.currentTimeMillis(),
            fileName = label,
            verdict = report.verdict,
            reason = report.summary,
            quarantined = quarantined,
        )
        _state.update { state ->
            state.copy(events = (listOf(event) + state.events).take(MAX_EVENTS))
        }
    }

    private companion object {
        const val DOWNLOAD_POLL_MS = 20_000L
        const val MAX_EVENTS = 25
        val SKIP_EXTENSIONS = setOf(
            "torrent", "db", "db-wal", "db-shm", "jsonl", "tmp", "part", "lock",
            "uid", "log", "nfo",
        )
    }
}
