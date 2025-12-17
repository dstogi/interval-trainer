package com.dstogi.intervaltrainer

import android.os.SystemClock
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.util.UUID
import kotlin.math.ceil

enum class PhaseType { WARMUP, WORK, REST, COOLDOWN }
enum class RunStatus { IDLE, RUNNING, PAUSED, FINISHED }

data class Exercise(
    val name: String,
    val notes: String = ""
)

data class TimingConfig(
    val warmupSec: Int = 0,
    val workSec: Int,
    val restBetweenRepsSec: Int = 0,
    val repsPerSet: Int = 1,
    val restBetweenSetsSec: Int = 0,
    val sets: Int,
    val cooldownSec: Int = 0
) {
    init {
        require(warmupSec >= 0)
        require(workSec >= 0)
        require(restBetweenRepsSec >= 0)
        require(repsPerSet > 0)
        require(restBetweenSetsSec >= 0)
        require(sets > 0)
        require(cooldownSec >= 0)
    }
}

data class IntervalCard(
    val id: String = UUID.randomUUID().toString(),
    val title: String,
    val timing: TimingConfig,
    val exercise: Exercise? = null
)

data class Phase(
    val type: PhaseType,
    val durationSec: Int,
    val label: String,
    val exercise: Exercise?
)

object PhaseBuilder {
    fun build(card: IntervalCard): List<Phase> {
        val t = card.timing
        val phases = mutableListOf<Phase>()

        if (t.warmupSec > 0) {
            phases += Phase(PhaseType.WARMUP, t.warmupSec, "Aufwärmen", null)
        }

        for (set in 1..t.sets) {
            for (rep in 1..t.repsPerSet) {
                phases += Phase(
                    type = PhaseType.WORK,
                    durationSec = t.workSec,
                    label = "Satz $set/${t.sets} · Wdh $rep/${t.repsPerSet}",
                    exercise = card.exercise
                )

                // Pause zwischen Wiederholungen (innerhalb Satz)
                if (rep < t.repsPerSet && t.restBetweenRepsSec > 0) {
                    phases += Phase(PhaseType.REST, t.restBetweenRepsSec, "Pause", null)
                }

                // Satzpause (zwischen Sätzen)
                if (rep == t.repsPerSet && set < t.sets && t.restBetweenSetsSec > 0) {
                    phases += Phase(PhaseType.REST, t.restBetweenSetsSec, "Satzpause", null)
                }
            }
        }

        if (t.cooldownSec > 0) {
            phases += Phase(PhaseType.COOLDOWN, t.cooldownSec, "Cooldown", null)
        }

        return phases
    }
}

data class RunUiState(
    val status: RunStatus = RunStatus.IDLE,
    val phaseIndex: Int = 0,
    val phaseCount: Int = 0,
    val phaseType: PhaseType = PhaseType.WORK,
    val label: String = "",
    val exerciseName: String? = null,
    val remainingSec: Int = 0,
    val totalRemainingSec: Int = 0
)

class IntervalSession(private val phases: List<Phase>) {

    var ui: RunUiState by mutableStateOf(initialUiState(phases))
        private set

    private var phaseStartMs: Long = 0L
    private var pausedAtMs: Long? = null
    private var pausedAccumMs: Long = 0L

    fun start() {
        if (phases.isEmpty()) return
        when (ui.status) {
            RunStatus.RUNNING -> return
            RunStatus.PAUSED -> resume()
            RunStatus.FINISHED -> beginPhase(0, nowMs())
            RunStatus.IDLE -> beginPhase(0, nowMs())
        }
    }

    fun pause() {
        if (ui.status != RunStatus.RUNNING) return
        pausedAtMs = nowMs()
        ui = ui.copy(status = RunStatus.PAUSED)
    }

    fun resume() {
        if (ui.status != RunStatus.PAUSED) return
        val p = pausedAtMs ?: return
        val n = nowMs()
        pausedAccumMs += (n - p)
        pausedAtMs = null
        ui = ui.copy(status = RunStatus.RUNNING)
    }

    fun stop() {
        ui = initialUiState(phases)
        pausedAtMs = null
        pausedAccumMs = 0L
        phaseStartMs = 0L
    }

    fun skip() {
        if (ui.status == RunStatus.IDLE) return
        beginPhase(ui.phaseIndex + 1, nowMs())
    }

    fun tick(nowMs: Long = nowMs()) {
        if (ui.status != RunStatus.RUNNING) return
        if (ui.phaseIndex !in phases.indices) return

        val phase = phases[ui.phaseIndex]
        val elapsedMs = nowMs - phaseStartMs - pausedAccumMs
        val remainingMs = (phase.durationSec * 1000L) - elapsedMs
        val remainingSec = ceil(remainingMs / 1000.0).toInt().coerceAtLeast(0)

        ui = ui.copy(
            remainingSec = remainingSec,
            totalRemainingSec = computeTotalRemainingSec(nowMs)
        )

        if (remainingMs <= 0L) {
            beginPhase(ui.phaseIndex + 1, nowMs)
        }
    }

    private fun beginPhase(index: Int, nowMs: Long) {
        if (index !in phases.indices) {
            ui = ui.copy(
                status = RunStatus.FINISHED,
                phaseIndex = phases.size,
                remainingSec = 0,
                totalRemainingSec = 0,
                label = "Fertig!",
                exerciseName = null
            )
            return
        }

        phaseStartMs = nowMs
        pausedAccumMs = 0L
        pausedAtMs = null

        val p = phases[index]
        ui = ui.copy(
            status = RunStatus.RUNNING,
            phaseIndex = index,
            phaseCount = phases.size,
            phaseType = p.type,
            label = p.label,
            exerciseName = p.exercise?.name,
            remainingSec = p.durationSec,
            totalRemainingSec = computeTotalRemainingSec(nowMs)
        )
    }

    private fun computeTotalRemainingSec(nowMs: Long): Int {
        if (ui.phaseIndex !in phases.indices) return 0

        val current = phases[ui.phaseIndex]
        val elapsedMs = nowMs - phaseStartMs - pausedAccumMs
        val currentRemainingMs = (current.durationSec * 1000L - elapsedMs).coerceAtLeast(0L)

        val restSec = phases.drop(ui.phaseIndex + 1).sumOf { it.durationSec }
        return ceil(currentRemainingMs / 1000.0).toInt() + restSec
    }

    private fun nowMs(): Long = SystemClock.elapsedRealtime()
}

fun formatDuration(sec: Int): String {
    val s = sec.coerceAtLeast(0)
    val m = s / 60
    val r = s % 60
    return "%02d:%02d".format(m, r)
}

fun parseDuration(input: String): Int? {
    val t = input.trim()
    if (t.isEmpty()) return 0

    return if (t.contains(":")) {
        val parts = t.split(":")
        when (parts.size) {
            2 -> {
                val m = parts[0].toIntOrNull() ?: return null
                val s = parts[1].toIntOrNull() ?: return null
                if (m < 0 || s !in 0..59) return null
                m * 60 + s
            }
            3 -> {
                val h = parts[0].toIntOrNull() ?: return null
                val m = parts[1].toIntOrNull() ?: return null
                val s = parts[2].toIntOrNull() ?: return null
                if (h < 0 || m !in 0..59 || s !in 0..59) return null
                h * 3600 + m * 60 + s
            }
            else -> null
        }
    } else {
        val s = t.toIntOrNull() ?: return null
        if (s < 0) null else s
    }
}

private fun initialUiState(phases: List<Phase>): RunUiState {
    val first = phases.firstOrNull()
    return RunUiState(
        status = RunStatus.IDLE,
        phaseIndex = 0,
        phaseCount = phases.size,
        phaseType = first?.type ?: PhaseType.WORK,
        label = first?.label ?: "",
        exerciseName = first?.exercise?.name,
        remainingSec = first?.durationSec ?: 0,
        totalRemainingSec = phases.sumOf { it.durationSec }
    )
}

