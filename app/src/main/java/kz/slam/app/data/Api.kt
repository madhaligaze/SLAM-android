package kz.slam.app.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** Ответ сервера с ошибкой (4xx/5xx). Сбой сети — обычный IOException. */
class ApiException(val status: Int, message: String) : Exception(message)

@Serializable
data class RemoteWorker(@SerialName("full_name") val fullName: String = "")

@Serializable
data class RemoteBlock(
    val id: Int,
    val name: String,
    val company: String = "",
    @SerialName("object_key") val objectKey: String = "sulphide_1",
    val task: String = "montazh",
    val workers: List<RemoteWorker> = emptyList(),
    @SerialName("last_job") val lastJob: ServerJobSummary? = null,
)

/**
 * Клиент сервера общих бригад (тот же API, что у веб-версии).
 * Форму сервер для телефона не заполняет — только хранит бригады и отметки.
 */
class Api(private val base: String) {

    suspend fun listBlocks(): List<RemoteBlock> = json.decodeFromString(request("GET", "/api/blocks"))

    suspend fun createBlock(name: String, company: String, objectKey: String, task: String): RemoteBlock =
        json.decodeFromString(request("POST", "/api/blocks", obj(
            "name" to name, "company" to company, "object_key" to objectKey, "task" to task)))

    suspend fun patchBlock(id: Int, name: String, company: String, objectKey: String, task: String) {
        request("PATCH", "/api/blocks/$id", obj(
            "name" to name, "company" to company, "object_key" to objectKey, "task" to task))
    }

    suspend fun putWorkers(id: Int, names: List<String>) {
        request("PUT", "/api/blocks/$id/workers", "{\"names\":${json.encodeToString(names)}}")
    }

    suspend fun deleteBlock(id: Int) {
        request("DELETE", "/api/blocks/$id")
    }

    /** Зарегистрировать отправку с телефона. 409 — бригада уже отправляется. */
    suspend fun phoneJobStart(blockId: Int, jobId: String, names: List<String>, submit: Boolean,
                              base: Map<String, String>) {
        request("POST", "/api/blocks/$blockId/phone-jobs",
            "{\"id\":${q(jobId)},\"names\":${json.encodeToString(names)},\"submit\":$submit," +
                "\"base\":${json.encodeToString(base)}}")
    }

    suspend fun phoneJobUpdate(jobId: String, status: String, done: Int, results: List<WorkerResult>,
                               fields: List<List<kz.slam.app.engine.FieldRecord>>?, finishedAt: String?) {
        val sb = StringBuilder("{\"status\":${q(status)},\"done\":$done,\"results\":${json.encodeToString(results)}")
        if (fields != null) sb.append(",\"fields\":").append(json.encodeToString(fields))
        if (finishedAt != null) sb.append(",\"finished_at\":").append(q(finishedAt))
        sb.append("}")
        request("PUT", "/api/phone-jobs/$jobId", sb.toString())
    }

    private suspend fun request(method: String, path: String, body: String? = null): String =
        withContext(Dispatchers.IO) {
            val c = URL(base.trimEnd('/') + path).openConnection() as HttpURLConnection
            try {
                c.requestMethod = method
                c.connectTimeout = 15_000
                c.readTimeout = 30_000
                c.setRequestProperty("Accept", "application/json")
                if (body != null) {
                    c.doOutput = true
                    c.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                    c.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
                }
                val code = c.responseCode
                val stream = if (code in 200..299) c.inputStream else c.errorStream
                val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
                if (code !in 200..299) throw ApiException(code, detail(text) ?: "HTTP $code")
                text
            } finally {
                c.disconnect()
            }
        }

    private fun detail(text: String): String? = runCatching {
        val d: JsonElement = (json.parseToJsonElement(text) as JsonObject)["detail"] ?: return null
        when (d) {
            is JsonObject -> d["message"]?.jsonPrimitive?.content
            else -> runCatching { d.jsonPrimitive.content }.getOrNull() ?: d.toString()
        }
    }.getOrNull()

    private fun q(s: String) = json.encodeToString(s)
    private fun obj(vararg kv: Pair<String, String>) =
        kv.joinToString(",", "{", "}") { (k, v) -> "${q(k)}:${q(v)}" }

    companion object {
        fun isNetwork(e: Throwable) = e is IOException
    }
}
