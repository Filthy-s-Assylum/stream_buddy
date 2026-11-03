package com.streambuddy.app.security

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.util.UUID

/**
 * App-private quarantine. Dangerous files are moved here (never to shared
 * storage), stripped of execute permission and kept until the user deletes
 * them. The manifest is append-only so the history survives restarts.
 */
class QuarantineStore(context: Context) {

    private val root: File = File(context.applicationContext.filesDir, "quarantine").apply {
        if (!exists()) mkdirs()
    }
    private val manifest = File(root, "_manifest.jsonl")

    @Synchronized
    fun quarantine(source: File, report: ScanReport): QuarantineEntry? {
        if (!source.exists() || !source.isFile) return null
        val id = UUID.randomUUID().toString().replace("-", "")
        val stored = File(root, "$id.bin")
        val moved = runCatching {
            if (source.renameTo(stored)) {
                true
            } else {
                source.inputStream().use { input ->
                    stored.outputStream().use { output -> input.copyTo(output) }
                }
                source.delete()
            }
        }.getOrDefault(false)
        if (!moved) {
            runCatching { stored.delete() }
            return null
        }
        stored.setReadable(false, false)
        stored.setWritable(false, false)
        stored.setExecutable(false, false)

        val entry = QuarantineEntry(
            id = id,
            originalName = report.fileName,
            storedName = stored.name,
            sizeBytes = report.sizeBytes,
            verdict = report.verdict,
            reason = report.summary,
            sha256 = report.sha256,
            quarantinedAt = System.currentTimeMillis(),
        )
        runCatching { appendManifest(entry) }
        return entry
    }

    @Synchronized
    fun entries(): List<QuarantineEntry> {
        if (!manifest.exists()) return emptyList()
        return runCatching {
            manifest.readLines().mapNotNull { line ->
                if (line.isBlank()) return@mapNotNull null
                runCatching {
                    val json = JSONObject(line)
                    QuarantineEntry(
                        id = json.getString("id"),
                        originalName = json.optString("originalName", "unknown"),
                        storedName = json.getString("storedName"),
                        sizeBytes = json.optLong("sizeBytes", 0L),
                        verdict = runCatching {
                            ScanVerdict.valueOf(json.getString("verdict"))
                        }.getOrDefault(ScanVerdict.MALICIOUS),
                        reason = json.optString("reason", ""),
                        sha256 = json.optString("sha256", ""),
                        quarantinedAt = json.optLong("quarantinedAt", 0L),
                    )
                }.getOrNull()
            }
        }.getOrDefault(emptyList())
    }

    @Synchronized
    fun totalBytes(): Long = entries().sumOf { it.sizeBytes }

    @Synchronized
    fun deleteAll(): Int {
        val count = entries().size
        runCatching { root.listFiles()?.forEach { file -> file.delete() } }
        return count
    }

    @Synchronized
    fun delete(entry: QuarantineEntry) {
        runCatching { File(root, entry.storedName).delete() }
        runCatching {
            if (!manifest.exists()) return@runCatching
            val kept = manifest.readLines().filterNot { line ->
                line.contains("\"id\":\"${entry.id}\"")
            }
            manifest.writeText(kept.joinToString("\n").let { if (it.isBlank()) "" else "$it\n" })
        }
    }

    private fun appendManifest(entry: QuarantineEntry) {
        val json = JSONObject().apply {
            put("id", entry.id)
            put("originalName", entry.originalName)
            put("storedName", entry.storedName)
            put("sizeBytes", entry.sizeBytes)
            put("verdict", entry.verdict.name)
            put("reason", entry.reason)
            put("sha256", entry.sha256)
            put("quarantinedAt", entry.quarantinedAt)
        }
        manifest.appendText(json.toString() + "\n")
    }
}
