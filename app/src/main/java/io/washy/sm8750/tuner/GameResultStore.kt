package io.washy.sm8750.tuner

import android.content.Context
import android.provider.Settings
import android.util.AtomicFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

internal class GameResultStore(private val context: Context) {
    private val file = AtomicFile(File(context.filesDir, "game-scenario-result.json"))

    private fun bootCount(): Int =
        Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT, -1)

    fun read(): GameResult? = runCatching {
        val json = JSONObject(file.readFully().decodeToString())
        val boot = json.optInt("boot", -1)
        val currentBoot = bootCount()
        val status = GameOutcome.valueOf(json.getString("outcome"))
        val outcome = if (status != GameOutcome.RUNNING) status
        else if (boot >= 0 && currentBoot >= 0 && boot != currentBoot) GameOutcome.REBOOTED
        else GameOutcome.INTERRUPTED
        val coverage = json.optJSONObject("coverage")?.let { saved ->
            GameCoverage(
                frequencies = saved.optJSONArray("frequencies")?.let { rows ->
                    List(rows.length()) { i ->
                        val row = rows.getJSONObject(i)
                        GameFrequency(
                            row.getString("policy"), row.getInt("up"), row.getInt("down"),
                            row.getInt("fast"), row.intOrNull("low"), row.intOrNull("high"),
                        )
                    }
                },
                cpuMoves = saved.intOrNull("cpuMoves"),
                clusterMoves = saved.intOrNull("clusterMoves"),
                gpuTimedRounds = saved.optInt("gpuTimed"),
                overlapRounds = saved.intOrNull("gpuOverlap"),
                incompleteTrace = saved.optBoolean("incomplete"),
            )
        }
        GameResult(
            outcome, json.getInt("elapsed"), json.getInt("rounds"), coverage,
            json.optString("detail").ifBlank { null },
            legacyScenario = json.optInt("scenarioVersion", 1) == 1,
        )
    }.getOrNull()

    fun write(result: GameResult) {
        val json = JSONObject()
            .put("boot", bootCount())
            .put("scenarioVersion", 2)
            .put("outcome", result.outcome.name)
            .put("elapsed", result.elapsed)
            .put("rounds", result.rounds)
            .put("detail", result.detail)
        result.coverage?.let { coverage ->
            val values = JSONObject()
                .put("cpuMoves", coverage.cpuMoves)
                .put("clusterMoves", coverage.clusterMoves)
                .put("gpuTimed", coverage.gpuTimedRounds)
                .put("gpuOverlap", coverage.overlapRounds)
                .put("incomplete", coverage.incompleteTrace)
            coverage.frequencies?.let { frequencies ->
                values.put("frequencies", JSONArray().apply {
                    for (item in frequencies) {
                        put(JSONObject().put("policy", item.policy).put("up", item.up).put("down", item.down)
                            .put("fast", item.fastPairs).put("low", item.lowKhz).put("high", item.highKhz))
                    }
                })
            }
            json.put("coverage", values)
        }
        val output = file.startWrite()
        try {
            output.write(json.toString().encodeToByteArray())
            file.finishWrite(output)
        } catch (e: Exception) {
            file.failWrite(output)
            throw e
        }
    }

    private fun JSONObject.intOrNull(key: String): Int? = if (isNull(key)) null else getInt(key)
}
