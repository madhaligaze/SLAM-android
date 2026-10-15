package kz.slam.app

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kz.slam.app.jobs.JobRequest
import kz.slam.app.jobs.JobState
import kz.slam.app.jobs.SubmitService

/**
 * Прототип: проверка движка на живой форме. Полноценный интерфейс
 * (бригады, состав, синхронизация) — следующий этап.
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (Build.VERSION.SDK_INT >= 33) requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        handleTestIntent(intent)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Scaffold { pad -> PrototypeScreen(Modifier.padding(pad)) { SubmitService.start(this, it) } }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleTestIntent(intent)
    }

    /** Запуск из adb для автотестов (только отладочная сборка). */
    private fun handleTestIntent(i: Intent?) {
        if (!BuildConfig.DEBUG || i?.getBooleanExtra("autostart", false) != true) return
        // Кириллица через adb из Windows доезжает битой — текстовые поля можно передать в base64 (суффикс 64).
        fun str(key: String): String? = i.getStringExtra(key + "64")
            ?.let { String(android.util.Base64.decode(it, android.util.Base64.DEFAULT), Charsets.UTF_8) }
            ?: i.getStringExtra(key)
        SubmitService.start(this, JobRequest(
            brigade = str("brigade") ?: "ТЕСТ",
            names = (str("names") ?: "ТЕСТ Приложение").split("|").map { it.trim() }.filter { it.isNotEmpty() },
            workplace = str("workplace") ?: "ТЕСТ — разработка приложения",
            task = i.getStringExtra("task") ?: "montazh",
            objectKey = i.getStringExtra("object") ?: "sulphide_1",
            company = str("company") ?: "ТЕСТ",
            submit = i.getBooleanExtra("submit", false),
        ))
        i.removeExtra("autostart")
    }
}

@Composable
private fun PrototypeScreen(modifier: Modifier, onStart: (JobRequest) -> Unit) {
    val job by SubmitService.state.collectAsStateWithLifecycle()
    var names by rememberSaveable { mutableStateOf("ТЕСТ Приложение 1") }
    var workplace by rememberSaveable { mutableStateOf("ТЕСТ — разработка приложения") }
    var company by rememberSaveable { mutableStateOf("ТЕСТ") }
    var task by rememberSaveable { mutableStateOf("montazh") }
    var obj by rememberSaveable { mutableStateOf("sulphide_1") }
    var submit by rememberSaveable { mutableStateOf(false) }

    LazyColumn(modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item { Text("SLAM — прототип движка", style = MaterialTheme.typography.titleLarge) }
        item { OutlinedTextField(names, { names = it }, Modifier.fillMaxWidth(), label = { Text("ФИО (по одному в строке)") }) }
        item { OutlinedTextField(workplace, { workplace = it }, Modifier.fillMaxWidth(), label = { Text("Рабочее место") }) }
        item { OutlinedTextField(company, { company = it }, Modifier.fillMaxWidth(), label = { Text("Подрядная организация") }) }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(task == "montazh", { task = "montazh" }, { Text("Монтаж") })
                FilterChip(task == "demontazh", { task = "demontazh" }, { Text("Демонтаж") })
                FilterChip(obj == "sulphide_1", { obj = "sulphide_1" }, { Text("Сульфид 1") })
                FilterChip(obj == "sulphide_2", { obj = "sulphide_2" }, { Text("Сульфид 2") })
            }
        }
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(submit, { submit = it })
                Spacer(Modifier.padding(4.dp))
                Text(if (submit) "Реально отправлять" else "Проверка без отправки")
            }
        }
        item {
            Button(
                enabled = job?.active != true,
                onClick = {
                    onStart(JobRequest("ТЕСТ", names.lines().map { it.trim() }.filter { it.isNotEmpty() },
                        workplace.trim(), task, obj, company.trim(), submit))
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text(if (job?.active == true) "Идёт отправка…" else "Запустить") }
        }
        job?.let { j -> item { JobView(j) } }
        job?.let { j -> items(j.results) { r ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp)) {
                    Text("${r.fullName} — ${r.status}${if (r.submitted) " (отправлено)" else ""} ${if (r.elapsed > 0) "${r.elapsed} с" else ""}")
                    if (r.fieldsTotal > 0) Text("Поля: ${r.fieldsVerified}/${r.fieldsTotal} сверено", style = MaterialTheme.typography.bodySmall)
                    r.errors.forEach { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                    r.warnings.forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
                }
            }
        } }
    }
}

@Composable
private fun JobView(j: JobState) {
    Column {
        Text("${if (j.active) "Идёт" else "Готово"}: ${j.done} из ${j.total}")
        Spacer(Modifier.height(6.dp))
        LinearProgressIndicator(progress = { if (j.total == 0) 0f else j.done.toFloat() / j.total }, Modifier.fillMaxWidth())
    }
}
