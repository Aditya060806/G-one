package com.gone.ai.health.emergency

import android.app.job.JobParameters
import android.app.job.JobService
import kotlinx.coroutines.*

class EmergencySyncJob : JobService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val jobs = mutableMapOf<Int, Job>()
    override fun onStartJob(params: JobParameters): Boolean {
        jobs[params.jobId] = scope.launch {
            val success = EmergencySync.sync(applicationContext)
            withContext(Dispatchers.Main) { jobs.remove(params.jobId); jobFinished(params, !success) }
        }
        return true
    }
    override fun onStopJob(params: JobParameters): Boolean { jobs.remove(params.jobId)?.cancel(); return true }
    override fun onDestroy() { scope.cancel(); super.onDestroy() }
}
