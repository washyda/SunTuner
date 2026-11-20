package io.washy.sm8750.tuner

import android.content.Context
import android.util.AtomicFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileNotFoundException

internal data class CrashLogHistory(
    val clearedAt: Long = 0,
    val entries: List<CrashEntry> = emptyList(),
    val clearedKernelIds: List<String> = emptyList(),
) {
    fun withScan(scan: CrashScan): CrashLogHistory {
        val alreadyCleared = scan.entries.filter { entry ->
            entry.time <= clearedAt || (entry.bootId != null && scan.bootStartedAt <= clearedAt)
        }
        val suppressed = suppressKernelLogs(alreadyCleared)
        val imported = scan.entries.filter { it !in alreadyCleared && it.id !in suppressed }
        val merged = imported.map { entry ->
            val previous = entries.firstOrNull { it.matches(entry) }
            when {
                previous == null -> entry
                entry.kind == CrashKind.UNKNOWN && previous.kind != CrashKind.UNKNOWN ->
                    previous.copy(
                        id = entry.id,
                        bootId = entry.bootId ?: previous.bootId,
                        evidence = (entry.evidence + previous.evidence).distinctBy { it.label },
                    )
                else -> entry.copy(
                    bootId = entry.bootId ?: previous.bootId,
                    thread = entry.thread ?: previous.thread,
                    frame = entry.frame ?: previous.frame,
                    cpu = entry.cpu ?: previous.cpu,
                    detail = entry.detail ?: previous.detail,
                    evidence = (entry.evidence + previous.evidence).distinctBy { it.label },
                )
            }
        }
        val retained = entries.filterNot { previous -> imported.any { previous.matches(it) } }
        return copy(
            entries = (merged + retained).sortedByDescending { it.time }.take(CRASH_LOG_LIMIT),
            clearedKernelIds = suppressed,
        )
    }

    private fun suppressKernelLogs(logs: List<CrashEntry>): List<String> =
        (logs.map { it.id }.filter { it.startsWith(KERNEL_LOG_ID_PREFIX) } + clearedKernelIds)
            .distinct().take(CRASH_LOG_LIMIT)

    private fun CrashEntry.matches(other: CrashEntry): Boolean =
        id == other.id || (bootId != null && bootId == other.bootId)
}

internal class CrashLogStore(context: Context) {
    private val file = AtomicFile(File(context.filesDir, "crash-log-history.json"))

    fun read(): CrashLogHistory {
        val json = try {
            JSONObject(file.readFully().decodeToString())
        } catch (_: FileNotFoundException) {
            return CrashLogHistory()
        }
        val rows = json.getJSONArray("entries")
        val entries = List(rows.length()) { index ->
            val row = rows.getJSONObject(index)
            CrashEntry(
                id = row.getString("id"), time = row.getLong("time"),
                kind = CrashKind.valueOf(row.getString("kind")), source = row.getString("source"),
                thread = row.optString("thread").ifBlank { null },
                frame = row.optString("frame").ifBlank { null },
                cpu = if (row.has("cpu")) row.getInt("cpu") else null,
                detail = row.optString("detail").ifBlank { null },
                bootId = row.optString("bootId").ifBlank { null },
                evidence = row.optJSONArray("evidence")?.let { records ->
                    List(records.length()) { i ->
                        val record = records.getJSONObject(i)
                        val args = record.getJSONArray("deleteArgs")
                        CrashEvidence(record.getString("label"), record.getString("text"), List(args.length()) { args.getString(it) })
                    }
                }.orEmpty(),
            )
        }
        val cleared = json.getJSONArray("clearedKernelIds")
        return CrashLogHistory(json.getLong("clearedAt"), entries, List(cleared.length()) { cleared.getString(it) })
    }

    fun write(history: CrashLogHistory) {
        val rows = JSONArray()
        for (entry in history.entries) {
            rows.put(
                JSONObject().put("id", entry.id).put("time", entry.time)
                    .put("kind", entry.kind.name).put("source", entry.source)
                    .put("thread", entry.thread).put("frame", entry.frame).put("cpu", entry.cpu)
                    .put("detail", entry.detail).put("bootId", entry.bootId)
                    .put("evidence", JSONArray().apply {
                        for (record in entry.evidence) {
                            put(JSONObject().put("label", record.label).put("text", record.text)
                                .put("deleteArgs", JSONArray(record.deleteArgs)))
                        }
                    }),
            )
        }
        val data = JSONObject().put("clearedAt", history.clearedAt).put("entries", rows)
            .put("clearedKernelIds", JSONArray(history.clearedKernelIds))
        val output = file.startWrite()
        try {
            output.write(data.toString().encodeToByteArray())
            file.finishWrite(output)
        } catch (e: Exception) {
            file.failWrite(output)
            throw e
        }
    }
}
