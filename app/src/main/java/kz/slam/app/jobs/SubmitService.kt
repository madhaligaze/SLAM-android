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
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kz.slam.app.MainActivity
import kz.slam.app.R
import kz.slam.app.data.Api
import kz.slam.app.data.ApiException
import kz.slam.app.data.JobEntity
import kz.slam.app.data.JobFieldsEntity
import kz.slam.app.data.Repo
import kz.slam.app.data.WorkerResult
import kz.slam.app.engine.FillResult
import kz.slam.app.engine.FormBrowser
import kz.slam.app.engine.SlamEngine
import kz.slam.app.engine.WorkerData
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** Что отправить: бригада, её данные на сегодня и список ФИО. */
data class JobRequest(
    val brigadeId: String,
    val serverBlockId: Int?,
    val brigadeName: String,
    val names: List<String>,
    val workplace: String,
    val task: String,
    val objectKey: String,
    val company: String,
    val submit: Boolean = true,
)

/**
 * Отправка бригад. Foreground-служба с уведомлением: Android не убивает
 * процесс, пока она идёт — бригадир может свернуть приложение или убрать
 * телефон, формы продолжают уходить.
 *
 * Очередь — это записи jobs со статусом queued в базе: можно поставить
 * несколько бригад подряд, они пройдут по одной (браузер один).
 */
class SubmitService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var wakeLock: PowerManager.WakeLock? = null
    private var loop: kotlinx.coroutines.Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        ensureChannel(this)
        ServiceCompat.startForeground(
            this, NOTIF_ID, notification("Подготовка…", 0, 0, ongoing = true),
            if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0,
        )
        if (wakeLock == null) {
            wakeLock = (getSystemService(POWER_SERVICE) as PowerManager)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "slam:submit")
                .apply { acquire(2 * 60 * 60 * 1000L) }
        }
        if (loop?.isActive != true) loop = scope.launch { runQueue() }
        return START_NOT_STICKY
    }

    private suspend fun runQueue() {
        _running.value = true
        val browser = FormBrowser(this)
        try {
            browser.start()
            val engine = SlamEngine(browser)
            while (true) {
                val job = Repo.db.jobs().active().firstOrNull { it.status == "queued" } ?: break
                runJob(job, engine)
            }
        } catch (e: Throwable) {
            Log.e(TAG, "queue crashed", e)
            // Всё, что не доделано, честно помечаем — иначе висело бы «идёт отправка».
            Repo.recoverInterrupted()
        } finally {
            runCatching { browser.stop() }
            _running.value = false
            wakeLock?.let { if (it.isHeld) it.release() }
            wakeLock = null
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopSelf()
            Repo.requestSync(0)
        }
    }

    private suspend fun runJob(queued: JobEntity, engine: SlamEngine) {
        var job = queued.copy(status = "running", startedAt = System.currentTimeMillis())
        save(job)
        Log.i(TAG, "JOB_START " + JSONObject().put("id", job.id).put("total", job.total).put("submit", job.submit))

        // Сначала — отметка на сервере: другие бригадиры увидят, что бригада
        // отправляется, а сервер не даст запустить её второй раз.
        var reporter: Api? = null
        val blockId = job.serverBlockId
        if (blockId != null) {
            try {
                val api = Repo.api()
                withTimeoutOrNull(15_000) {
                    api.phoneJobStart(blockId, job.id, job.results.map { it.fullName }, job.submit, Repo.baseOf(job))
                } ?: throw java.io.IOException("timeout")
                job = job.copy(onServer = true)
                reporter = api
            } catch (e: ApiException) {
                if (e.status == 409) {
                    val why = "Не отправлялось: по этой бригаде уже идёт отправка — с другого телефона или из веб-версии."
                    job = job.copy(status = "error", finishedAt = System.currentTimeMillis(), reported = true,
                        note = "По этой бригаде уже идёт отправка с другого устройства. Дождитесь её окончания.",
                        results = job.results.map { it.copy(status = "failed", errors = listOf(why)) })
                    save(job)
                    logDone(job)
                    return
                }
                job = job.copy(note = "Сервер не принял отметку (${e.message}) — формы отправляются без неё.")
            } catch (e: Exception) {
                job = job.copy(note = "Нет связи с сервером — другие бригадиры увидят эту отправку, когда связь появится.")
            }
            save(job)
        }

        for (i in job.results.indices) {
            if (job.id in stopRequested) {
                job = job.copy(results = job.results.mapIndexed { j, r ->
                    if (j >= i) r.copy(status = "failed", errors = r.errors + "Не отправлялось: отправку остановили.") else r
                }, note = "Отправку остановили вручную.")
                break
            }
            job = job.withResult(i) { it.copy(status = "running") }
            save(job)
            notifyProgress(job)
            reporter = report(reporter, job)

            val name = job.results[i].fullName
            val res = engine.fillOne(WorkerData(name, job.workplace, job.task, job.objectKey, job.company), job.submit)
            Repo.db.jobs().upsertFields(JobFieldsEntity(job.id, i, res.fields.toList()))
            job = job.withResult(i) { res.toWorkerResult(name) }
            save(job)
            logResult(i, name, res)
            reporter = report(reporter, job)
        }
        stopRequested.remove(job.id)
        job = job.copy(status = "done", finishedAt = System.currentTimeMillis())
        save(job)
        notifyProgress(job)

        // Итог с протоколом полей — на сервер; не вышло — передаст синхронизация.
        if (reporter != null) {
            try {
                val fields = Repo.db.jobs().allFields(job.id).associate { it.idx to it.fields }
                withTimeoutOrNull(30_000) {
                    reporter.phoneJobUpdate(job.id, job.status, job.done, job.results,
                        job.results.indices.map { fields[it] ?: emptyList() }, Repo.iso(job.finishedAt))
                    true
                }?.let { job = job.copy(reported = true); save(job) }
            } catch (e: Exception) {
                Log.w(TAG, "final report failed: $e")
            }
        }
        notifyDone(job)
        logDone(job)
    }

    /** Ход отправки — на сервер. Сеть подвела — дальше не пытаемся, итог передаст синхронизация. */
    private suspend fun report(api: Api?, job: JobEntity): Api? {
        if (api == null) return null
        return try {
            withTimeoutOrNull(8_000) { api.phoneJobUpdate(job.id, "running", job.done, job.results, null, null); api }
        } catch (e: Exception) {
            null
        }
    }

    private suspend fun save(job: JobEntity) = Repo.db.jobs().upsert(job)

    override fun onDestroy() {
        scope.cancel()
        wakeLock?.let { if (it.isHeld) it.release() }
        super.onDestroy()
    }

    // ---------- уведомления ----------

    private fun notifyProgress(job: JobEntity) {
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(NOTIF_ID, notification("«${job.brigadeName}»: ${job.done} из ${job.total}", job.done, job.total, ongoing = true))
    }

    private fun notifyDone(job: JobEntity) {
        val bad = job.failed + job.unconfirmed
        val text = when {
            !job.submit -> "«${job.brigadeName}»: проверено ${job.done} из ${job.total}, без отправки"
            bad == 0 -> "«${job.brigadeName}»: отправлено ${job.sent} из ${job.total}"
            else -> "«${job.brigadeName}»: отправлено ${job.sent} из ${job.total}, проблем: $bad"
        }
        getSystemService(NotificationManager::class.java)
            .notify(job.id.hashCode(), notification(text, 0, 0, ongoing = false, brigadeId = job.brigadeId))
    }

    private fun notification(text: String, done: Int, total: Int, ongoing: Boolean, brigadeId: String? = null): Notification {
        val open = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            brigadeId?.let { putExtra(MainActivity.EXTRA_BRIGADE, it) }
        }
        val b = NotificationCompat.Builder(this, if (ongoing) CHANNEL else CHANNEL_DONE)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(if (ongoing) "SLAM — идёт отправка" else "SLAM — отправка завершена")
            .setContentText(text)
            .setOnlyAlertOnce(true)
            .setOngoing(ongoing)
            .setAutoCancel(!ongoing)
            .setContentIntent(PendingIntent.getActivity(this, brigadeId.hashCode(), open,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
        if (ongoing && total > 0) b.setProgress(total, done, false)
        return b.build()
    }

    // ---------- строки в logcat для автотестов ----------

    private fun logResult(i: Int, name: String, r: FillResult) {
        val o = JSONObject()
            .put("i", i).put("name", name).put("status", r.status).put("submitted", r.submitted)
            .put("elapsed", r.elapsed)
            .put("verified", r.fields.count { it.verified }).put("fields", r.fields.size)
            .put("errors", JSONArray(r.errors)).put("warnings", JSONArray(r.warnings))
            .put("steps", JSONArray(r.steps))
        Log.i(TAG, "RESULT $o")
    }

    private fun logDone(job: JobEntity) {
        Log.i(TAG, "JOB_DONE " + JSONObject().put("id", job.id).put("status", job.status).put("sent", job.sent)
            .put("bad", job.failed + job.unconfirmed).put("onServer", job.onServer).put("reported", job.reported)
            .put("note", job.note))
    }

    companion object {
        const val TAG = "SLAM"
        private const val CHANNEL = "progress"         // ход отправки — тихо
        private const val CHANNEL_DONE = "done"        // итог — со звуком: телефон, скорее всего, в кармане
        private const val NOTIF_ID = 1

        private val _running = MutableStateFlow(false)
        val running: StateFlow<Boolean> = _running.asStateFlow()
        private val stopRequested = java.util.Collections.synchronizedSet(mutableSetOf<String>())

        /** Поставить бригаду в очередь и запустить службу. Возвращает id отправки. */
        suspend fun enqueue(context: Context, r: JobRequest): String {
            val id = UUID.randomUUID().toString().replace("-", "")
            Repo.db.jobs().upsert(JobEntity(
                id = id, brigadeId = r.brigadeId, brigadeName = r.brigadeName, serverBlockId = r.serverBlockId,
                status = "queued", submit = r.submit, workplace = r.workplace, task = r.task,
                objectKey = r.objectKey, company = r.company,
                results = r.names.map { WorkerResult(it) }, createdAt = System.currentTimeMillis(),
            ))
            ContextCompat.startForegroundService(context, Intent(context, SubmitService::class.java))
            return id
        }

        /** Остановить после текущей формы (начатую форму не бросаем на полпути). */
        fun requestStop(jobId: String) { stopRequested += jobId }

        fun ensureChannel(context: Context) {
            val nm = context.getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, "Ход отправки", NotificationManager.IMPORTANCE_LOW))
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_DONE, "Отправка завершена", NotificationManager.IMPORTANCE_DEFAULT))
        }
    }
}

private fun JobEntity.withResult(i: Int, f: (WorkerResult) -> WorkerResult) =
    copy(results = results.mapIndexed { j, r -> if (j == i) f(r) else r })

fun FillResult.toWorkerResult(name: String) = WorkerResult(
    fullName = name, status = status, submitted = submitted, elapsed = elapsed,
    errors = errors.toList(), warnings = warnings.toList(), steps = steps.toList(),
    fieldsTotal = fields.size, fieldsVerified = fields.count { it.verified },
)
