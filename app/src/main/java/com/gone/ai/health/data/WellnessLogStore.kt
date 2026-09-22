package com.gone.ai.health.data

import android.content.Context
import com.gone.ai.health.domain.SleepLog
import com.gone.ai.health.domain.StressLog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.time.LocalDate

data class WellnessLogs(val sleep: List<SleepLog> = emptyList(), val stress: List<StressLog> = emptyList())

/** Small, bounded, private journal. Shared-preference cloud backups are excluded by app rules. */
class WellnessLogStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("local_wellness_logs", Context.MODE_PRIVATE)
    private val mutable = MutableStateFlow(read())
    val logs = mutable.asStateFlow()

    private fun read(): WellnessLogs {
        val sleep = prefs.getStringSet("sleep", emptySet()).orEmpty().mapNotNull { row -> runCatching {
            val p = row.split('|')
            SleepLog(LocalDate.parse(p[0]), p[1].toInt(), p[2].toInt(), p[3].toInt(), p[4].toInt()).also { it.validate(LocalDate.now()) }
        }.getOrNull() }.sortedByDescending { it.wakeDate }
        val stress = prefs.getStringSet("stress", emptySet()).orEmpty().mapNotNull { row -> runCatching {
            val p = row.split('|')
            StressLog(LocalDate.parse(p[0]), p[1].toInt()).also { it.validate(LocalDate.now()) }
        }.getOrNull() }.sortedByDescending { it.date }
        return WellnessLogs(sleep, stress)
    }

    fun save(log: SleepLog) {
        log.validate(LocalDate.now())
        write(mutable.value.copy(sleep = (mutable.value.sleep.filterNot { it.wakeDate == log.wakeDate } + log).sortedByDescending { it.wakeDate }.take(365)))
    }
    fun save(log: StressLog) {
        log.validate(LocalDate.now())
        write(mutable.value.copy(stress = (mutable.value.stress.filterNot { it.date == log.date } + log).sortedByDescending { it.date }.take(365)))
    }
    fun deleteSleep(date: LocalDate) = write(mutable.value.copy(sleep = mutable.value.sleep.filterNot { it.wakeDate == date }))
    fun deleteStress(date: LocalDate) = write(mutable.value.copy(stress = mutable.value.stress.filterNot { it.date == date }))
    fun clear() = write(WellnessLogs())

    private fun write(value: WellnessLogs) {
        prefs.edit().putStringSet("sleep", value.sleep.map { "${it.wakeDate}|${it.bedtimeMinute}|${it.wakeMinute}|${it.awakeMinutes}|${it.quality}" }.toSet())
            .putStringSet("stress", value.stress.map { "${it.date}|${it.level}" }.toSet()).apply()
        mutable.value = value
    }
}
