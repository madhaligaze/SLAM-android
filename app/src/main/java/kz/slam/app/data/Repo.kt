package kz.slam.app.data

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Instant
import java.util.UUID

/** Состояние связи с сервером общих бригад — для значка в шапке. */
data class SyncState(
    val running: Boolean = false,
    val online: Boolean? = null,     // null — ещё не пробовали
    val message: String = "",
    val at: Long = 0,
)

/**
 * Данные приложения. Всё, что видит бригадир, лежит в базе на телефоне —
 * без сервера приложение работает целиком. Пока сервер доступен:
 *  - свои правки уходят на него, чужие приходят (бригады общие);
 *  - отправки регистрируются там, и другие видят «отправляется / отправлено».
 */
object Repo {
    private const val TAG = "SLAM"
    lateinit var db: AppDatabase
        private set
    lateinit var prefs: Prefs
        private set
    /** Живёт дольше экранов: отложенное сохранение не должно пропасть при уходе с экрана. */
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun init(context: Context) {
        if (::db.isInitialized) return
        db = AppDatabase.open(context.applicationContext)
        prefs = Prefs(context.applicationContext)
    }

    fun api() = Api(prefs.server)

    // ---------- бригады ----------

    val brigades: Flow<List<BrigadeEntity>> get() = db.brigades().observeAll()
    fun brigade(id: String): Flow<BrigadeEntity?> = db.brigades().observe(id)

    suspend fun createBrigade(name: String): String {
        val now = System.currentTimeMillis()
        val id = UUID.randomUUID().toString()
        db.brigades().upsert(BrigadeEntity(id = id, name = name.trim(), createdAt = now, updatedAt = now))
        requestSync()
        return id
    }

    /** Сохранить правку бригады. updatedAt — метка этой правки (по ней экран узнаёт свою запись). */
    suspend fun saveBrigade(b: BrigadeEntity, updatedAt: Long) {
        val cur = db.brigades().get(b.id) ?: return
        db.brigades().upsert(b.copy(serverId = cur.serverId, serverJob = cur.serverJob,
            deleted = cur.deleted, dirty = true, updatedAt = updatedAt))
        requestSync()
    }

    suspend fun deleteBrigade(id: String) {
        val b = db.brigades().get(id) ?: return
        if (b.serverId == null) db.brigades().delete(id)
        else db.brigades().upsert(b.copy(deleted = true, dirty = true, updatedAt = System.currentTimeMillis()))
        prefs.forget(id)
        requestSync()
    }

    // ---------- отправки ----------

    val jobs: Flow<List<JobEntity>> get() = db.jobs().observeAll()
    fun latestJob(brigadeId: String): Flow<JobEntity?> = db.jobs().observeLatest(brigadeId)

    /** Отправка, оборванная вместе с процессом приложения (телефон убил его, перезагрузка). */
    suspend fun recoverInterrupted() {
        for (j in db.jobs().active()) {
            db.jobs().upsert(j.copy(
                status = "error",
                finishedAt = j.finishedAt ?: System.currentTimeMillis(),
                note = "Отправка прервалась: приложение было закрыто системой.",
                results = j.results.map {
                    when (it.status) {
                        "running" -> it.copy(status = "unconfirmed", errors = it.errors +
                            "Приложение закрылось во время заполнения этой формы — неизвестно, ушла ли она.")
                        "pending" -> it.copy(status = "failed", errors = it.errors + "Не отправлялось: приложение закрылось раньше.")
                        else -> it
                    }
                },
            ))
        }
    }

    // ---------- синхронизация ----------

    private val _sync = MutableStateFlow(SyncState())
    val sync: StateFlow<SyncState> = _sync.asStateFlow()
    private val syncMutex = Mutex()
    private var pending: Job? = null

    /** Синхронизировать чуть позже — правки идут пачкой, на каждую букву ходить незачем. */
    fun requestSync(delayMs: Long = 1_000) {
        pending?.cancel()
        pending = scope.launch { delay(delayMs); syncNow() }
    }

    suspend fun syncNow(): Boolean = syncMutex.withLock {
        _sync.value = _sync.value.copy(running = true)
        val api = api()
        val ok = try {
            push(api)
            pull(api)
            reportJobs(api)
            _sync.value = SyncState(online = true, message = "Синхронизировано", at = System.currentTimeMillis())
            true
        } catch (e: Exception) {
            Log.w(TAG, "sync failed: $e")
            val msg = if (Api.isNetwork(e)) "Нет связи с сервером — работаем с данными на телефоне"
            else "Сервер ответил ошибкой: ${e.message}"
            _sync.value = SyncState(online = false, message = msg, at = System.currentTimeMillis())
            false
        }
        ok
    }

    /** Свои правки — на сервер. */
    private suspend fun push(api: Api) {
        for (b in db.brigades().all().filter { it.dirty }) {
            if (b.deleted) {
                b.serverId?.let { sid ->
                    try { api.deleteBlock(sid) } catch (e: ApiException) { if (e.status != 404) throw e }
                }
                db.brigades().delete(b.id)
                continue
            }
            var sid = b.serverId
            if (sid == null) {
                sid = api.createBlock(b.name, b.company, b.objectKey, b.task).id
                db.brigades().setServerId(b.id, sid)
            } else {
                try {
                    api.patchBlock(sid, b.name, b.company, b.objectKey, b.task)
                } catch (e: ApiException) {
                    if (e.status != 404) throw e
                    // Бригаду удалили на сервере, а здесь её правили — свои правки не теряем.
                    sid = api.createBlock(b.name, b.company, b.objectKey, b.task).id
                    db.brigades().setServerId(b.id, sid)
                }
            }
            api.putWorkers(sid, b.names)
            db.brigades().markClean(b.id, b.updatedAt)
        }
    }

    /** Чужие правки — с сервера. Свои неотправленные не перетираются. */
    private suspend fun pull(api: Api) {
        val remote = api.listBlocks()
        val locals = db.brigades().all()
        val bySid = locals.filter { it.serverId != null }.associateBy { it.serverId!! }
        val now = System.currentTimeMillis()
        for (r in remote) {
            val names = r.workers.map { it.fullName }
            val l = bySid[r.id]
            if (l == null) {
                db.brigades().upsert(BrigadeEntity(
                    id = UUID.randomUUID().toString(), serverId = r.id, name = r.name, company = r.company,
                    task = r.task, objectKey = r.objectKey, workers = names, createdAt = now, updatedAt = now,
                    dirty = false, serverJob = r.lastJob))
                continue
            }
            if (!l.dirty && (l.name != r.name || l.company != r.company || l.task != r.task ||
                    l.objectKey != r.objectKey || l.names != names)) {
                db.brigades().applyRemote(l.id, l.updatedAt, r.name, r.company, r.task, r.objectKey, names, now)
            }
            if (l.serverJob != r.lastJob) db.brigades().setServerJob(l.id, r.lastJob)
        }
        // Удалённые на сервере (другим бригадиром) — убираем, если здесь их не правили.
        val remoteIds = remote.map { it.id }.toSet()
        for (l in locals) {
            if (l.serverId != null && l.serverId !in remoteIds && !l.dirty) db.brigades().delete(l.id)
        }
    }

    /** Итоги отправок, которые не удалось передать сразу (не было связи). */
    private suspend fun reportJobs(api: Api) {
        for (j in db.jobs().unreported()) {
            val blockId = j.serverBlockId ?: continue
            try {
                if (!j.onServer) {
                    api.phoneJobStart(blockId, j.id, j.results.map { it.fullName }, j.submit, baseOf(j))
                }
                val fields = db.jobs().allFields(j.id).associate { it.idx to it.fields }
                api.phoneJobUpdate(j.id, j.status, j.done, j.results,
                    j.results.indices.map { fields[it] ?: emptyList() }, iso(j.finishedAt))
                db.jobs().upsert(j.copy(onServer = true, reported = true))
            } catch (e: ApiException) {
                // 409 — по бригаде сейчас идёт другая отправка: передадим позже.
                // 404 — бригаду удалили: передавать некуда.
                if (e.status == 404) db.jobs().upsert(j.copy(reported = true))
                else if (e.status != 409) throw e
            }
        }
        db.jobs().trim()
        db.jobs().trimFields()
    }

    fun baseOf(j: JobEntity) = mapOf(
        "workplace" to j.workplace, "task" to j.task, "object_key" to j.objectKey,
        "company" to j.company, "device" to prefs.device,
    )

    fun iso(ms: Long?): String? = ms?.let { Instant.ofEpochMilli(it).toString() }
}
