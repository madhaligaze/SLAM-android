package kz.slam.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import kz.slam.app.BuildConfig
import kz.slam.app.data.Repo

@Composable
fun NameDialog(title: String, label: String, initial: String, ok: String,
               onDismiss: () -> Unit, onOk: (String) -> Unit) {
    var value by remember { mutableStateOf(TextFieldValue(initial, TextRange(0, initial.length))) }
    val focus = remember { FocusRequester() }
    val submit = { value.text.trim().takeIf { it.isNotEmpty() }?.let(onOk) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(value, { value = it }, label = { Text(label) }, singleLine = true,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { submit() }),
                modifier = Modifier.fillMaxWidth().focusRequester(focus))
        },
        confirmButton = { Button({ submit() }, enabled = value.text.isNotBlank()) { Text(ok) } },
        dismissButton = { TextButton(onDismiss) { Text("Отмена") } },
    )
    LaunchedEffect(Unit) { focus.requestFocus() }
}

@Composable
fun ConfirmDialog(title: String, message: String, ok: String, danger: Boolean = false,
                  onDismiss: () -> Unit, onOk: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(message, color = C.muted) },
        confirmButton = {
            Button(onOk, colors = ButtonDefaults.buttonColors(containerColor = if (danger) C.danger else C.accent)) { Text(ok) }
        },
        dismissButton = { TextButton(onDismiss) { Text("Отмена") } },
    )
}

/** Перед реальной отправкой бригадир видит каждое ФИО и все общие данные. */
@Composable
fun SubmitDialog(names: List<String>, duplicates: List<String>, busyElsewhere: Boolean,
                 summary: List<Pair<String, String>>, onDismiss: () -> Unit, onOk: () -> Unit) {
    val n = names.size
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Отправить форму SLAM?") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text("Уйдёт $n ${plural(n, "реальная форма", "реальные формы", "реальных форм")} — по одной на каждого. Проверьте состав:",
                    color = C.muted)
                Column(Modifier.padding(vertical = 10.dp).fillMaxWidth().heightIn(max = 220.dp)
                    .background(C.bg2, RoundedCornerShape(10.dp)).verticalScroll(rememberScrollState())
                    .padding(horizontal = 12.dp, vertical = 8.dp)) {
                    names.forEachIndexed { i, s -> Text("${i + 1}. $s", fontSize = 15.sp, modifier = Modifier.padding(vertical = 2.dp)) }
                }
                if (duplicates.isNotEmpty()) {
                    Text("Повторяется: ${duplicates.joinToString(", ")} — на ${if (duplicates.size > 1) "них" else "него"} форма уйдёт дважды.",
                        color = C.warn, fontSize = 13.sp, modifier = Modifier.padding(bottom = 8.dp))
                }
                if (busyElsewhere) {
                    Text("По данным сервера эта бригада сейчас отправляется с другого устройства. " +
                        "Если отправка там ещё идёт, сервер не даст отправить её второй раз.",
                        color = C.warn, fontSize = 13.sp, modifier = Modifier.padding(bottom = 8.dp))
                }
                Column(Modifier.fillMaxWidth().background(C.bg2, RoundedCornerShape(10.dp)).padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    summary.forEach { (k, v) ->
                        Row {
                            Text(k, color = C.muted, fontSize = 13.sp)
                            Spacer(Modifier.width(10.dp))
                            Text(v, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.End,
                                modifier = Modifier.weight(1f))
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(onOk, colors = ButtonDefaults.buttonColors(containerColor = C.accent)) { Text("Отправить ($n)") }
        },
        dismissButton = { TextButton(onDismiss) { Text("Отмена") } },
    )
}

@Composable
fun SettingsDialog(onDismiss: () -> Unit) {
    var server by remember { mutableStateOf(Repo.prefs.server) }
    var device by remember { mutableStateOf(Repo.prefs.device) }
    var status by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Настройки") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(server, { server = it }, label = { Text("Сервер общих бригад") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth())
                Text("Без сервера приложение работает целиком, но бригады видны только на этом телефоне.",
                    fontSize = 12.sp, color = C.muted2)
                OutlinedTextField(device, { device = it }, label = { Text("Имя телефона") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth())
                Text("Под этим именем отправки с телефона видны другим бригадирам.", fontSize = 12.sp, color = C.muted2)
                if (status.isNotEmpty()) Text(status, fontSize = 13.sp)
                Text("Версия ${BuildConfig.VERSION_NAME}", fontSize = 12.sp, color = C.muted2)
            }
        },
        confirmButton = {
            Button({
                Repo.prefs.server = server
                Repo.prefs.device = device
                status = "Проверяю связь…"
                scope.launch { status = if (Repo.syncNow()) "Связь есть, бригады обновлены." else Repo.sync.value.message }
            }) { Text("Сохранить") }
        },
        dismissButton = { TextButton(onDismiss) { Text("Закрыть") } },
    )
}
