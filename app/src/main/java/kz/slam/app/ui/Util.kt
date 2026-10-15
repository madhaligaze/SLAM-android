package kz.slam.app.ui

import kz.slam.app.data.BrigadeEntity
import kz.slam.app.data.JobEntity
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

fun plural(n: Int, one: String, few: String, many: String): String {
    val m10 = n % 10
    val m100 = n % 100
    return when {
        m10 == 1 && m100 != 11 -> one
        m10 in 2..4 && (m100 < 10 || m100 >= 20) -> few
        else -> many
    }
}

fun objLabel(k: String) = if (k == "sulphide_2") "Сульфидная фабрика 2" else "Сульфидная фабрика 1"
fun objShort(k: String) = if (k == "sulphide_2") "Сульфид 2" else "Сульфид 1"
fun taskLabel(k: String) = if (k == "demontazh") "Демонтаж лесов" else "Монтаж лесов"
fun taskShort(k: String) = if (k == "demontazh") "Демонтаж" else "Монтаж"

/** Для сравнения ФИО: регистр, «ё» и лишние пробелы не важны. */
fun normName(s: String) = s.lowercase().replace('ё', 'е').split(Regex("\\s+")).filter { it.isNotEmpty() }.joinToString(" ")

private val hm = DateTimeFormatter.ofPattern("HH:mm")
private val dm = DateTimeFormatter.ofPattern("dd.MM")

/** «сегодня 08:12» / «вчера 17:40» / «14.10 08:12». */
fun fmtWhen(ms: Long?): String {
    if (ms == null || ms <= 0) return ""
    val zone = ZoneId.systemDefault()
    val t = Instant.ofEpochMilli(ms).atZone(zone)
    val days = LocalDate.now(zone).toEpochDay() - t.toLocalDate().toEpochDay()
    return when (days) {
        0L -> "сегодня " + t.format(hm)
        1L -> "вчера " + t.format(hm)
        else -> t.format(dm) + " " + t.format(hm)
    }
}

fun parseIso(s: String?): Long? = s?.let { runCatching { Instant.parse(it.replace("+00:00", "Z")).toEpochMilli() }.getOrNull()
    ?: runCatching { java.time.OffsetDateTime.parse(it).toInstant().toEpochMilli() }.getOrNull() }

enum class Tone { OK, WARN, BAD, RUN, MUTED }

/** Строка «чем кончилась последняя отправка» — для списка и экрана бригады. */
data class JobBadge(val tone: Tone, val text: String, val active: Boolean)

/**
 * Последняя отправка бригады: своя (с этого телефона) или по данным сервера —
 * с другого телефона или из веб-версии. Берём ту, что новее.
 */
fun jobBadge(b: BrigadeEntity, local: JobEntity?): JobBadge? {
    val s = b.serverJob
    val serverTime = parseIso(s?.createdAt) ?: 0
    if (local != null && (s == null || local.id == s.id || local.createdAt >= serverTime)) {
        val when_ = fmtWhen(local.finishedAt ?: local.createdAt)
        return when {
            local.status == "queued" -> JobBadge(Tone.RUN, "В очереди на отправку", true)
            local.status == "running" -> JobBadge(Tone.RUN, "Отправляется: ${local.done} из ${local.total}", true)
            !local.submit -> JobBadge(Tone.MUTED, "Проверка без отправки · $when_", false)
            local.status == "error" && local.sent == 0 && local.unconfirmed == 0 ->
                JobBadge(Tone.BAD, "Не отправлено · $when_", false)
            local.status == "error" -> JobBadge(Tone.BAD, "Прервано: отправлено ${local.sent} из ${local.total} · $when_", false)
            local.failed + local.unconfirmed > 0 -> JobBadge(Tone.WARN, "Отправлено ${local.sent} из ${local.total} · $when_", false)
            else -> JobBadge(Tone.OK, "Отправлено ${local.sent} из ${local.total} · $when_", false)
        }
    }
    if (s == null) return null
    val when_ = fmtWhen(parseIso(s.finishedAt) ?: serverTime)
    val other = " (другое устройство)"
    return when {
        s.status == "queued" -> JobBadge(Tone.RUN, "В очереди на отправку$other", true)
        s.status == "running" -> JobBadge(Tone.RUN, "Отправляется: ${s.done} из ${s.total}$other", true)
        !s.submit -> JobBadge(Tone.MUTED, "Проверка без отправки · $when_", false)
        s.status == "error" -> JobBadge(Tone.BAD, "Прервано: отправлено ${s.sent} из ${s.total} · $when_", false)
        s.failed + s.unconfirmed > 0 -> JobBadge(Tone.WARN, "Отправлено ${s.sent} из ${s.total} · $when_", false)
        else -> JobBadge(Tone.OK, "Отправлено ${s.sent} из ${s.total} · $when_", false)
    }
}
