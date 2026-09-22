package com.gone.ai.health.service

import android.content.Context
import android.util.Log
import androidx.core.content.edit
import com.gone.ai.ai.repository.AIRepository
import com.gone.ai.health.data.SessionReportEntity
import com.gone.ai.health.data.SessionRepository
import com.gone.ai.health.session.ReportPdfContent
import com.gone.ai.health.session.ReportPdfWriter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The files that go with a session report: its PDF, and the plain-language summary that is
 * added to both the report and the PDF.
 *
 * When a session ends, the PDF is written at once from the deterministic report. Then, if
 * automatic summaries are on, the on-device model rewords the observations; a summary that
 * passes [com.gone.ai.health.session.ReportSummaryPrompt]'s checks is attached and the PDF is
 * written again with it. A summary that fails the checks changes nothing.
 *
 * Runs in its own scope so leaving the report screen does not abandon the work.
 */
class ReportArtifacts private constructor(context: Context) {

    /** What exists for one report and what is still being made. */
    data class Status(
        val pdf: File? = null,
        val writingPdf: Boolean = false,
        val addingSummary: Boolean = false,
        /** A problem worth telling the person, e.g. a PDF that could not be written. */
        val problem: String? = null
    )

    private val appContext = context.applicationContext
    private val sessions = SessionRepository.from(appContext)
    private val summarizer = LlamaReportSummarizer(AIRepository.getInstance(appContext))
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val fileLock = Mutex()
    private val summaryLock = Mutex()

    private val _status = MutableStateFlow<Map<Long, Status>>(emptyMap())
    val status: StateFlow<Map<Long, Status>> = _status.asStateFlow()

    private fun update(reportId: Long, change: (Status) -> Status) =
        _status.update { it + (reportId to change(it[reportId] ?: Status())) }

    /** A session has just ended with this report. */
    fun onReportCreated(reportId: Long) {
        scope.launch {
            writePdf(reportId)
            if (autoSummaries(appContext)) addSummary(reportId)
        }
    }

    /** The person asked for a summary. Returns why it was not written, or null when it was. */
    suspend fun summarizeNow(reportId: Long): String? = addSummary(reportId)

    /** The PDF for sharing, saving or viewing, written now if it is missing. */
    suspend fun ensurePdf(reportId: Long): File? = withContext(Dispatchers.IO) {
        val report = sessions.reportById(reportId) ?: return@withContext null
        pdfFile(report).takeIf { it.exists() } ?: writePdf(reportId)
    }

    fun onReportDeleted(report: SessionReportEntity) {
        scope.launch {
            fileLock.withLock { pdfFile(report).delete() }
            _status.update { it - report.id }
        }
    }

    fun pdfFile(report: SessionReportEntity): File {
        val stamp = SimpleDateFormat("yyyy-MM-dd-HHmm", Locale.US).format(Date(report.startedAt))
        return File(File(appContext.filesDir, DIRECTORY), "G-one-report-$stamp-${report.id}.pdf")
    }

    private suspend fun writePdf(reportId: Long): File? = fileLock.withLock {
        val report = sessions.reportById(reportId) ?: return@withLock null
        update(reportId) { it.copy(writingPdf = true, problem = null) }
        try {
            val (events, readings) = sessions.pdfSources(report)
            val file = pdfFile(report)
            ReportPdfWriter().write(ReportPdfContent.from(report, events, readings), file)
            update(reportId) { it.copy(pdf = file, writingPdf = false) }
            file
        } catch (e: Exception) {
            Log.e(TAG, "Report PDF failed", e)
            update(reportId) { it.copy(writingPdf = false, problem = "The PDF could not be written: ${e.message ?: "unknown error"}") }
            null
        }
    }

    private suspend fun addSummary(reportId: Long): String? = summaryLock.withLock {
        val report = sessions.reportById(reportId) ?: return@withLock "The report no longer exists."
        if (report.aiSummary != null) return@withLock null
        update(reportId) { it.copy(addingSummary = true) }
        try {
            when (val result = summarizer.summarize(report)) {
                is LlamaReportSummarizer.Result.Written -> {
                    sessions.attachAiSummary(reportId, result.text)
                    writePdf(reportId)
                    null
                }
                is LlamaReportSummarizer.Result.NotWritten -> result.reason
            }
        } finally {
            update(reportId) { it.copy(addingSummary = false) }
        }
    }

    companion object {
        private const val TAG = "ReportArtifacts"
        const val DIRECTORY = "reports"
        private const val PREFS = "gone_preferences"
        private const val KEY_AUTO_SUMMARY = "report_auto_summary"

        @Volatile private var INSTANCE: ReportArtifacts? = null

        fun getInstance(context: Context): ReportArtifacts =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: ReportArtifacts(context).also { INSTANCE = it }
            }

        /** Whether a summary is written automatically when a session ends. On unless switched off. */
        fun autoSummaries(context: Context): Boolean =
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_AUTO_SUMMARY, true)

        fun setAutoSummaries(context: Context, enabled: Boolean) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit { putBoolean(KEY_AUTO_SUMMARY, enabled) }
        }
    }
}
