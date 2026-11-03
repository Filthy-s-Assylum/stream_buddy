package com.streambuddy.app.security

/**
 * Result of inspecting one file's name, size and leading bytes. This is
 * deliberately independent of Android so it can be unit-tested on the JVM.
 */
enum class ScanVerdict {
    CLEAN,
    SUSPICIOUS,
    MALICIOUS;

    /** Only a malicious verdict is quarantined. Suspicious files stay visible. */
    val blocksAccess: Boolean get() = this == MALICIOUS
}

enum class ThreatCategory {
    EXECUTABLE,
    ANDROID_PACKAGE,
    SCRIPT,
    MACRO_DOCUMENT,
    ACTIVE_DOCUMENT,
    ARCHIVE,
    EXTENSION_MISMATCH,
    UNKNOWN_BINARY,
    UNKNOWN_TEXT,
    EMPTY_FILE,
    SCAN_DISABLED,
}

data class ScanFinding(
    val category: ThreatCategory,
    val detail: String,
)

data class ScanReport(
    val fileName: String,
    val sizeBytes: Long,
    val verdict: ScanVerdict,
    val findings: List<ScanFinding>,
    val sha256: String,
) {
    val blocked: Boolean get() = verdict.blocksAccess

    val summary: String
        get() = when (verdict) {
            ScanVerdict.CLEAN -> "No known executable or macro payload found."
            ScanVerdict.SUSPICIOUS -> findings.joinToString("; ") { it.detail }
            ScanVerdict.MALICIOUS -> findings.joinToString("; ") { it.detail }
        }
}

data class QuarantineEntry(
    val id: String,
    val originalName: String,
    val storedName: String,
    val sizeBytes: Long,
    val verdict: ScanVerdict,
    val reason: String,
    val sha256: String,
    val quarantinedAt: Long,
)
