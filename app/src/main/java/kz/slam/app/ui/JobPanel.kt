package kz.slam.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kz.slam.app.data.JobEntity
import kz.slam.app.data.Repo
import kz.slam.app.data.ServerJobSummary
import kz.slam.app.data.WorkerResult
import kz.slam.app.engine.FieldRecord

/**
 * Ход и итог последней отправки бригады. Отправка идёт в службе, а не на
 * экране: можно уйти, свернуть приложение, заблокировать телефон — здесь
 * всё будет, когда вернётесь.
 */
@Composable
fun JobPanel(job: JobEntity?, server: ServerJobSummary?, onRetry: (List<String>) -> Unit, onStop: () -> Unit) {
    // Отправка с другого устройства новее своей — показываем её сводку.
    val serverNewer = server != null && server.id != job?.id &&
        (parseIso(server.createdAt) ?: 0) > (job?.createdAt ?: 0)
    if (serverNewer) {
        ServerJobLine(server!!)
        return
    }
    if (job == null) return
    val active = job.active
    Column(Modifier.padding(top = 16.dp).fillMaxWidth()) {
        Spacer(Modifier.fillMaxWidth().height(1.dp).background(C.line))
        Row(Modifier.padding(top = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(when {
                job.status == "queued" -> "В очереди"
                active -> "Идёт отправка"
                !job.submit -> "Последняя проверка (без отправки)"
                else -> "Последняя отправка"
            }, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, modifier = Modifier.weight(1f))
            Text(fmtWhen(job.finishedAt ?: job.startedAt ?: job.createdAt), fontSize = 12.sp, color = C.muted2)
        }
        LinearProgressIndicator(
            progress = { if (job.total == 0) 0f else job.done.toFloat() / job.total },
            modifier = Modifier.padding(vertical = 8.dp).fillMaxWidth().height(8.dp),
            color = C.accent, trackColor = C.bg2, drawStopIndicator = {},
        )
        Text(statusText(job), fontSize = 13.sp, color = C.muted)
        if (job.note.isNotEmpty()) Text(job.note, fontSize = 12.5.sp, color = C.warn, modifier = Modifier.padding(top = 4.dp))
        if (active) {
            Text("Отправка идёт в фоне — телефон можно убрать, приложение свернуть. Итог придёт уведомлением и будет здесь.",
                fontSize = 12.5.sp, color = C.muted, modifier = Modifier.padding(top = 8.dp).fillMaxWidth()
                    .background(C.bg2, RoundedCornerShape(9.dp)).border(1.dp, C.line2, RoundedCornerShape(9.dp))
                    .padding(horizontal = 11.dp, vertical = 9.dp))
            if (job.status == "running" && job.total - job.done > 1) {
                OutlinedButton(onStop, Modifier.padding(top = 8.dp).fillMaxWidth()) { Text("Остановить после текущей формы") }
            }
        }
        Spacer(Modifier.height(10.dp))
        job.results.forEachIndexed { i, r -> ResultCard(job.id, i, r) }

        // Кто не ушёл точно — можно отправить заново одним нажатием.
        // «Не подтверждено» сюда не попадает: форма могла уйти, повтор дал бы дубль.
        val failed = job.results.filter { it.status == "failed" }.map { it.fullName }
        if (!active && job.submit && failed.isNotEmpty()) {
            OutlinedButton({ onRetry(failed) }, Modifier.padding(top = 4.dp).fillMaxWidth()) {
                Text("Отправить заново ${failed.size} ${plural(failed.size, "неотправленного", "неотправленных", "неотправленных")}")
            }
        }
        if (!active && job.unconfirmed > 0) {
            Text("«Не подтверждено» — форма могла уйти. Проверьте в ответах формы, прежде чем отправлять этих людей снова.",
                fontSize = 12.sp, color = C.warn, modifier = Modifier.padding(top = 6.dp))
        }
    }
}

private fun statusText(job: JobEntity): String = when {
    job.status == "queued" -> "Ждёт, пока закончится отправка другой бригады на этом телефоне."
    job.active -> {
        val fin = job.results.filter { it.elapsed > 0 }
        val avg = if (fin.isEmpty()) 25.0 else fin.sumOf { it.elapsed } / fin.size
        val left = ((job.total - job.done) * avg).toInt()
        "Обработано ${job.done} из ${job.total} • осталось ~" + if (left >= 90) "${left / 60} мин" else "${maxOf(5, left)} с"
    }
    !job.submit -> "Проверено ${job.done} из ${job.total}, в форму ничего не отправлялось."
    else -> buildString {
        append(if (job.status == "error") "Прервано: " else "Готово: ")
        append("отправлено ${job.sent} из ${job.total}")
        if (job.failed > 0) append(", с ошибкой ${job.failed}")
        if (job.unconfirmed > 0) append(", без подтверждения ${job.unconfirmed}")
    }
}

@Composable
private fun ServerJobLine(s: ServerJobSummary) {
    val active = s.status == "running" || s.status == "queued"
    Column(Modifier.padding(top = 16.dp).fillMaxWidth()) {
        Spacer(Modifier.fillMaxWidth().height(1.dp).background(C.line))
        Text(if (active) "Отправляется с другого устройства" else "Последняя отправка (с другого устройства)",
            fontWeight = FontWeight.SemiBold, fontSize = 15.sp, modifier = Modifier.padding(top = 14.dp))
        val text = if (active) "Обработано ${s.done} из ${s.total}" else buildString {
            append("${fmtWhen(parseIso(s.finishedAt ?: s.createdAt))}: отправлено ${s.sent} из ${s.total}")
            if (s.failed > 0) append(", с ошибкой ${s.failed}")
            if (s.unconfirmed > 0) append(", без подтверждения ${s.unconfirmed}")
        }
        Text(text, fontSize = 13.sp, color = C.muted, modifier = Modifier.padding(top = 4.dp))
    }
}

@Composable
private fun ResultCard(jobId: String, index: Int, r: WorkerResult) {
    val (color, label) = when {
        r.status == "ok" && r.submitted -> C.accent to "отправлено"
        r.status == "ok" -> C.accent to "заполнено"
        r.status == "unconfirmed" -> C.warn to "не подтверждено"
        r.status == "failed" -> C.danger to "ошибка"
        r.status == "running" -> Color(0xFF4A8BE6) to "заполняется…"
        else -> C.muted2 to "ожидает"
    }
    Row(Modifier.padding(bottom = 8.dp).fillMaxWidth().background(C.bg2, RoundedCornerShape(10.dp))) {
        Spacer(Modifier.width(4.dp).height(48.dp).background(color, RoundedCornerShape(topStart = 10.dp, bottomStart = 10.dp)))
        Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp).weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(r.fullName, fontWeight = FontWeight.Bold, fontSize = 14.sp, modifier = Modifier.weight(1f, fill = false))
                Spacer(Modifier.width(8.dp))
                if (r.status == "running") androidx.compose.material3.CircularProgressIndicator(Modifier.size(12.dp), color = color, strokeWidth = 2.dp)
                else Spacer(Modifier.size(8.dp).background(color, RoundedCornerShape(4.dp)))
                Spacer(Modifier.width(5.dp))
                Text(label, color = color, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, modifier = Modifier.weight(1f))
                if (r.elapsed > 0) Text("${r.elapsed} с", fontSize = 11.5.sp, color = C.muted2)
            }
            r.errors.forEach { Text(it, fontSize = 12.sp, color = Color(0xFFEEBB99), modifier = Modifier.padding(top = 5.dp)) }
            r.warnings.forEach { Text(it, fontSize = 12.sp, color = C.warn, modifier = Modifier.padding(top = 5.dp)) }
            if (r.fieldsTotal > 0) WrittenFields(jobId, index, r)
            if (r.steps.isNotEmpty()) {
                var open by remember { mutableStateOf(false) }
                Text(if (open) "▾ ход выполнения" else "▸ ход выполнения", fontSize = 12.sp, color = C.muted2,
                    modifier = Modifier.padding(top = 6.dp).clickable { open = !open })
                if (open) Text(r.steps.joinToString("\n"), fontSize = 12.sp, color = C.muted, fontFamily = FontFamily.Monospace,
                    modifier = Modifier.padding(top = 6.dp).fillMaxWidth().background(C.bg, RoundedCornerShape(8.dp)).padding(8.dp))
            }
        }
    }
}

/** «Что записано в форму» — фактический протокол движка, грузится при раскрытии. */
@Composable
private fun WrittenFields(jobId: String, index: Int, r: WorkerResult) {
    var open by remember { mutableStateOf(false) }
    var fields by remember { mutableStateOf<List<FieldRecord>?>(null) }
    LaunchedEffect(open) { if (open && fields == null) fields = Repo.db.jobs().fields(jobId, index)?.fields ?: emptyList() }
    val bad = r.fieldsTotal - r.fieldsVerified
    Column(Modifier.padding(top = 9.dp).fillMaxWidth().background(C.bg, RoundedCornerShape(10.dp))
        .border(1.dp, C.line, RoundedCornerShape(10.dp))) {
        Row(Modifier.fillMaxWidth().clickable { open = !open }.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Icon(if (open) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown, null, tint = C.muted2, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text("Что записано в форму", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            Text("${r.fieldsVerified}/${r.fieldsTotal} проверено", fontSize = 11.5.sp, color = if (bad > 0) C.danger else C.accent)
        }
        if (open) {
            Column(Modifier.padding(start = 12.dp, end = 12.dp, bottom = 10.dp)) {
                val list = fields
                if (list == null) Text("Загрузка…", fontSize = 12.sp, color = C.muted2)
                else if (list.isEmpty()) Text("Протокол не сохранился.", fontSize = 12.sp, color = C.muted2)
                else {
                    var section = ""
                    list.forEach { f ->
                        if (f.section != section) {
                            section = f.section
                            Text(section.uppercase(), fontSize = 11.sp, color = C.muted2, fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(top = 10.dp, bottom = 4.dp))
                        }
                        PlanRow(f.q + if (f.verified) "  ✓ сверено" else "  ✗ не сверено", f.value,
                            f.note, empty = "— пусто —")
                    }
                }
            }
        }
    }
}
