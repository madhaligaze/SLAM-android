package kz.slam.app.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kz.slam.app.data.BrigadeEntity
import kz.slam.app.data.Repo

/** Строка состава. key — стабильный ключ строки для списка и фокуса. */
data class WorkerRow(val key: Long, val text: String)

/**
 * Состояние экрана бригады.
 *
 * Поля ввода живут здесь, а не читаются из базы на каждый символ: иначе
 * курсор прыгал бы. Правки пишутся в базу с короткой задержкой, а при уходе
 * с экрана — сразу (урок веб-версии: отложенное сохранение, о котором забыли
 * при выходе, и было причиной «состав не сохраняется»). Сохранение идёт в
 * области Repo.scope — оно переживает закрытие экрана.
 *
 * Если ту же бригаду поменяли на другом телефоне, новые данные подхватываются,
 * пока здесь нет несохранённых правок.
 */
class BrigadeEditor(private val id: String) {
    var loaded by mutableStateOf(false); private set
    var gone by mutableStateOf(false); private set
    var name by mutableStateOf("")
    var company by mutableStateOf("")
    var task by mutableStateOf("montazh")
    var objectKey by mutableStateOf("sulphide_1")
    var workplace by mutableStateOf("")
    val workers = mutableStateListOf<WorkerRow>()

    /** То, что бригадир видит и правит (без служебных полей синхронизации). */
    private data class Content(val name: String, val company: String, val task: String,
                               val objectKey: String, val workers: List<String>)

    private var base: BrigadeEntity? = null
    private var shown: Content? = null      // что сейчас загружено/сохранено с экрана
    private var ownWrite = -1L
    private var unsaved = false
    private var saveJob: Job? = null
    private var nextKey = 1L

    /**
     * Запись из базы. Поля экрана перечитываются ТОЛЬКО при настоящей чужой
     * правке. Служебные записи синхронизации (номер на сервере, «выгружено»,
     * сводка отправки) содержимого не меняют — перечитывание на них
     * пересобирало строки прямо во время ввода: только что добавленная строка
     * пропадала, и фамилия уходила не туда.
     */
    fun onEntity(e: BrigadeEntity?) {
        if (e == null) { if (loaded) gone = true; return }
        base = e
        val c = Content(e.name, e.company, e.task, e.objectKey, e.workers)
        if (loaded) {
            if (unsaved) return                  // поверх несохранённого ввода — никогда
            if (c == shown) return               // содержимое то же — служебная запись
            if (e.updatedAt <= ownWrite) return  // запоздалое эхо нашей же прежней записи
        }
        shown = c
        name = c.name; company = c.company; task = c.task; objectKey = c.objectKey
        workers.clear()
        c.workers.forEach { workers += WorkerRow(nextKey++, it) }
        if (!loaded) {
            workplace = Repo.prefs.workplace(id)
            loaded = true
        }
    }

    fun newKey() = nextKey++

    /** Любая правка полей бригады или состава. */
    fun changed() {
        unsaved = true
        saveJob?.cancel()
        saveJob = Repo.scope.launch { delay(400); save() }
    }

    /** Ушли с экрана — сохранить сразу, не дожидаясь задержки. */
    fun flushNow() {
        if (!unsaved) return
        saveJob?.cancel()
        saveJob = Repo.scope.launch { save() }
    }

    private suspend fun save() {
        val b = base ?: return
        if (!unsaved) return
        val now = System.currentTimeMillis()
        ownWrite = now
        unsaved = false
        val c = Content(name.trim().ifEmpty { b.name },     // пустое название сервер не примет
            company, task, objectKey, workers.map { it.text })
        shown = c
        Repo.saveBrigade(b.copy(name = c.name, company = c.company, task = c.task,
            objectKey = c.objectKey, workers = c.workers), now)
    }

    fun updateWorkplace(v: String) {
        workplace = v
        Repo.prefs.setWorkplace(id, v)
    }

    val names get() = workers.map { it.text.trim() }.filter { it.isNotEmpty() }

    /** Повторы ФИО (без учёта регистра, «ё» и пробелов) — форма на них ушла бы дважды. */
    fun duplicates(): List<String> {
        val seen = HashSet<String>()
        val out = LinkedHashSet<String>()
        names.forEach { if (!seen.add(normName(it))) out += it }
        return out.toList()
    }

    fun isDuplicateAt(index: Int): Boolean {
        val k = normName(workers[index].text)
        return k.isNotEmpty() && workers.take(index).any { normName(it.text) == k }
    }
}
