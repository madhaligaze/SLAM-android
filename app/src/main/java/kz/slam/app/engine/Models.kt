package kz.slam.app.engine

import kotlinx.serialization.Serializable

/** Данные одного работника для одной формы. */
data class WorkerData(
    val fullName: String,
    val workplace: String,
    val task: String,          // montazh | demontazh
    val objectKey: String,     // sulphide_1 | sulphide_2
    val company: String,       // пойдёт в «Другое» вопроса 6
)

/** Одно поле протокола: что реально оказалось в форме. */
@Serializable
data class FieldRecord(
    val section: String,
    val q: String,
    val kind: String,          // text | choice | choice+other
    val value: String,
    val verified: Boolean,
    val note: String = "",
    val key: String = "",
)

/**
 * Итог по одному работнику. Правила те же, что у серверного движка:
 * submitted=true — ТОЛЬКО при распознанной странице подтверждения;
 * нажали «Отправить», но подтверждения нет — статус «unconfirmed».
 */
class FillResult {
    var ok = true
    var submitted = false
    var unconfirmed = false
    val steps = mutableListOf<String>()
    val errors = mutableListOf<String>()
    val warnings = mutableListOf<String>()
    val fields = mutableListOf<FieldRecord>()
    var elapsed = 0.0

    val status: String
        get() = when {
            !ok || errors.isNotEmpty() -> "failed"
            unconfirmed -> "unconfirmed"
            else -> "ok"
        }

    fun log(msg: String) { steps += msg }
    fun warn(msg: String) { warnings += msg }
    fun fail(msg: String) { errors += msg; ok = false }

    fun record(section: String, question: String, kind: String, value: String,
               verified: Boolean, note: String = "", key: String = "") {
        fields += FieldRecord(section, question, kind, value, verified, note, key)
    }
}
