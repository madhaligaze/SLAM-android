package kz.slam.app.jobs

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kz.slam.app.MainActivity
import kz.slam.app.R
import kz.slam.app.engine.FillResult
import kz.slam.app.engine.FormBrowser
import kz.slam.app.engine.SlamEngine
import kz.slam.app.engine.WorkerData
import org.json.JSONArray
import org.json.JSONObject

/** Что отправить: общие данные бригады + список ФИО. */
data class JobRequest(
    val brigade: String,
    val names: List<String>,
    val workplace: String,
    val task: String,
    val objectKey: String,
    val company: String,
    val submit: Boolean,
)

data class WorkerResult(
    val fullName: String,
    val status: String = "pending",       // pending | running | ok | unconfirmed | failed
    val submitted: Boolean = false,
    val elapsed: Double = 0.0,
    val errors: List<String> = emptyList(),
    val warnings: List<String> = emptyList(),
    val steps: List<String> = emptyList(),
    val fieldsTotal: Int = 0,
    val fieldsVerified: Int = 0,
)

data class JobState(
    val request: JobRequest,
    val status: String,                   // running | done | error
    val results: List<WorkerResult>,
    val startedAt: Long,
    val finishedAt: Long? = null,
) {
    val done get() = results.count { it.status !in setOf("pending", "running") }
    val total get() = results.size
    val active get() = status == "running"
}

/**
 * Отправка бригады. Foreground-служба с уведомлением: Android не убивает
 * процесс, пока она идёт, и бригадир может свернуть приложение или
 * заблокировать телефон — формы продолжают уходить.
 */
class SubmitService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val req = pending ?: run { stopSelf(); return START_NOT_STICKY }
        pending = null
        ensureChannel(this)
        ServiceCompat.startForeground(
            this, NOTIF_ID, progressNotification(req.brigade, 0, req.names.size),
            if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0,
        )
        wakeLock = (getSystemService(POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "slam:submit")
            .apply { acquire(60 * 60 * 1000L) }
        scope.launch { runJob(req) }
        return START_NOT_STICKY
    }

    private suspend fun runJob(req: JobRequest) {
        _state.value = JobState(req, "running", req.names.map { WorkerResult(it) }, System.currentTimeMillis())
        Log.i(TAG, "JOB_START " + JSONObject().put("total", req.names.size).put("submit", req.submit))
        val browser = FormBrowser(this)
        var crashed: Throwable? = null
        try {
            browser.start()
            val engine = SlamEngine(browser)
            for ((i, name) in req.names.withIndex()) {
                setResult(i) { it.copy(status = "running") }
                val res = engine.fillOne(
                    WorkerData(name, req.workplace, req.task, req.objectKey, req.company), req.submit)
                setResult(i) { res.toWorkerResult(name) }
                logResult(req, i, res)
                notifyProgress(req.brigade, i + 1, req.names.size)
            }
        } catch (e: Throwable) {
            crashed = e
            Log.e(TAG, "job crashed", e)
        } finally {
            runCatching { browser.stop() }
        }
        _state.update { s ->
            s?.copy(
                status = if (crashed == null) "done" else "error",
                finishedAt = System.currentTimeMillis(),
                results = s.results.map {
                    if (it.status in setOf("pending", "running"))
                        it.copy(status = "failed", errors = it.errors + "Отправка прервана: ${crashed?.message}")
                    else it
                },
            )
        }
        finish()
    }

    private fun setResult(i: Int, f: (WorkerResult) -> WorkerResult) {
        _state.update { s -> s?.copy(results = s.results.mapIndexed { j, r -> if (j == i) f(r) else r }) }
    }

    private fun finish() {
        val s = _state.value
        val nm = getSystemService(NotificationManager::class.java)
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        if (s != null) {
            val sent = s.results.count { it.submitted }
            val bad = s.results.count { it.status == "failed" || it.status == "unconfirmed" }
            val text = if (s.request.submit) "Отправлено $sent из ${s.total}" + (if (bad > 0) ", проблем: $bad" else "")
            else "Проверено ${s.done} из ${s.total} (без отправки)"
            nm.notify(DONE_NOTIF_ID, baseNotification("«${s.request.brigade}»: $text").setAutoCancel(true).build())
            Log.i(TAG, "JOB_DONE " + JSONObject().put("status", s.status).put("sent", sent).put("bad", bad))
        }
        wakeLock?.let { if (it.isHeld) it.release() }
        stopSelf()
    }

    override fun onDestroy() {
        scope.cancel()
        wakeLock?.let { if (it.isHeld) it.release() }
        super.onDestroy()
    }

    private fun notifyProgress(brigade: String, done: Int, total: Int) {
        getSystemService(NotificationManager::class.java).notify(NOTIF_ID, progressNotification(brigade, done, total))
    }

    private fun progressNotification(brigade: String, done: Int, total: Int): Notification =
        baseNotification("«$brigade»: $done из $total")
            .setOngoing(true)
            .setProgress(total, done, false)
            .build()

    private fun baseNotification(text: String) = NotificationCompat.Builder(this, CHANNEL)
        .setSmallIcon(R.drawable.ic_notification)
        .setContentTitle("SLAM — отправка")
        .setContentText(text)
        .setOnlyAlertOnce(true)
        .setContentIntent(PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))

    /** Строка в logcat для автотестов: по ней видно итог без экрана. */
    private fun logResult(req: JobRequest, i: Int, r: FillResult) {
        val o = JSONObject()
            .put("i", i).put("name", req.names[i]).put("status", r.status).put("submitted", r.submitted)
            .put("elapsed", r.elapsed)
            .put("verified", r.fields.count { it.verified }).put("fields", r.fields.size)
            .put("errors", JSONArray(r.errors)).put("warnings", JSONArray(r.warnings))
            .put("steps", JSONArray(r.steps))
        Log.i(TAG, "RESULT $o")
    }

    companion object {
        const val TAG = "SLAM"
        private const val CHANNEL = "submit"
        private const val NOTIF_ID = 1
        private const val DONE_NOTIF_ID = 2

        private val _state = MutableStateFlow<JobState?>(null)
        val state: StateFlow<JobState?> = _state.asStateFlow()
        @Volatile private var pending: JobRequest? = null

        fun start(context: Context, req: JobRequest) {
            if (_state.value?.active == true) return
            pending = req
            ContextCompat.startForegroundService(context, Intent(context, SubmitService::class.java))
        }

        fun ensureChannel(context: Context) {
            val nm = context.getSystemService(NotificationManager::class.java)
            if (nm.getNotificationChannel(CHANNEL) == null) {
                nm.createNotificationChannel(
                    NotificationChannel(CHANNEL, "Отправка SLAM", NotificationManager.IMPORTANCE_LOW))
            }
        }
    }
}

fun FillResult.toWorkerResult(name: String) = WorkerResult(
    fullName = name, status = status, submitted = submitted, elapsed = elapsed,
    errors = errors.toList(), warnings = warnings.toList(), steps = steps.toList(),
    fieldsTotal = fields.size, fieldsVerified = fields.count { it.verified },
)
