package kz.slam.app.data

import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.TypeConverter
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kz.slam.app.engine.FieldRecord

val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; explicitNulls = false }

/**
 * Бригада. Источник правды для экрана — эта таблица на телефоне: приложение
 * работает и без сервера. Пока сервер доступен, бригады синхронизируются с
 * ним и становятся общими для всех бригадиров.
 */
@Entity(tableName = "brigades")
data class BrigadeEntity(
    @PrimaryKey val id: String,              // локальный UUID
    val serverId: Int? = null,               // id на сервере; null — ещё не выгружена
    val name: String,
    val company: String = "",
    val task: String = "montazh",            // montazh | demontazh
    val objectKey: String = "sulphide_1",    // sulphide_1 | sulphide_2
    val workers: List<String> = emptyList(), // ФИО по порядку
    val createdAt: Long,
    val updatedAt: Long,
    val dirty: Boolean = true,               // есть правки, ещё не отправленные на сервер
    val deleted: Boolean = false,            // удалена здесь, удаление ещё не отправлено
    val serverJob: ServerJobSummary? = null, // последняя отправка по данным сервера (с любого устройства)
) {
    val names get() = workers.map { it.trim() }.filter { it.isNotEmpty() }
}

/** Сводка последней отправки бригады, как её отдаёт сервер (GET /api/blocks → last_job). */
@Serializable
data class ServerJobSummary(
    val id: String,
    val status: String,
    val submit: Boolean = true,
    val total: Int = 0,
    val done: Int = 0,
    val sent: Int = 0,
    val failed: Int = 0,
    val unconfirmed: Int = 0,
    @SerialName("created_at") val createdAt: String? = null,
    @SerialName("finished_at") val finishedAt: String? = null,
)

/** Итог по одному работнику — тот же формат, что у сервера (уходит туда как есть). */
@Serializable
data class WorkerResult(
    @SerialName("full_name") val fullName: String,
    val status: String = "pending",          // pending | running | ok | unconfirmed | failed
    val submitted: Boolean = false,
    val elapsed: Double = 0.0,
    val errors: List<String> = emptyList(),
    val warnings: List<String> = emptyList(),
    val steps: List<String> = emptyList(),
    @SerialName("fields_total") val fieldsTotal: Int = 0,
    @SerialName("fields_verified") val fieldsVerified: Int = 0,
    @SerialName("worker_id") val workerId: Int? = null,
)

/** Отправка бригады с этого телефона. */
@Entity(tableName = "jobs")
data class JobEntity(
    @PrimaryKey val id: String,              // тот же id регистрируется на сервере
    val brigadeId: String,
    val brigadeName: String,
    val serverBlockId: Int?,
    val status: String,                      // queued | running | done | error
    val submit: Boolean,
    val workplace: String,
    val task: String,
    val objectKey: String,
    val company: String,
    val results: List<WorkerResult>,
    val createdAt: Long,
    val startedAt: Long? = null,
    val finishedAt: Long? = null,
    val note: String = "",                   // пояснение ко всей отправке (например, почему не началась)
    val onServer: Boolean = false,           // зарегистрирована на сервере (её видят другие)
    val reported: Boolean = false,           // итог передан на сервер
) {
    val active get() = status == "queued" || status == "running"
    val done get() = results.count { it.status !in setOf("pending", "running") }
    val total get() = results.size
    val sent get() = results.count { it.submitted }
    val failed get() = results.count { it.status == "failed" }
    val unconfirmed get() = results.count { it.status == "unconfirmed" }
}

/** Протокол полей одного работника — грузится, только когда его раскрыли. */
@Entity(tableName = "job_fields", primaryKeys = ["jobId", "idx"])
data class JobFieldsEntity(val jobId: String, val idx: Int, val fields: List<FieldRecord>)

class Converters {
    @TypeConverter fun fromStrings(v: List<String>): String = json.encodeToString(v)
    @TypeConverter fun toStrings(v: String): List<String> = json.decodeFromString(v)
    @TypeConverter fun fromResults(v: List<WorkerResult>): String = json.encodeToString(v)
    @TypeConverter fun toResults(v: String): List<WorkerResult> = json.decodeFromString(v)
    @TypeConverter fun fromFields(v: List<FieldRecord>): String = json.encodeToString(v)
    @TypeConverter fun toFields(v: String): List<FieldRecord> = json.decodeFromString(v)
    @TypeConverter fun fromSummary(v: ServerJobSummary?): String? = v?.let { json.encodeToString(it) }
    @TypeConverter fun toSummary(v: String?): ServerJobSummary? = v?.let { json.decodeFromString(it) }
}
