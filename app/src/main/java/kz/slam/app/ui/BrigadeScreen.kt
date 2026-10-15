package kz.slam.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.withFrameNanos
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import kz.slam.app.data.Repo
import kz.slam.app.engine.Plan
import kz.slam.app.jobs.JobRequest
import kz.slam.app.jobs.SubmitService

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun BrigadeScreen(id: String, onBack: () -> Unit) {
    val entity by Repo.brigade(id).collectAsStateWithLifecycle(initialValue = null)
    val job by Repo.latestJob(id).collectAsStateWithLifecycle(initialValue = null)
    val ed = remember(id) { BrigadeEditor(id) }
    LaunchedEffect(entity) { ed.onEntity(entity) }
    DisposableEffect(id) { onDispose { ed.flushNow() } }

    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val snack = remember { SnackbarHostState() }
    var confirmSubmit by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var invalid by remember { mutableStateOf(setOf<String>()) }
    val focus = remember { mutableStateMapOf<Long, FocusRequester>() }
    var focusKey by remember { mutableStateOf<Long?>(null) }
    val back = { ed.flushNow(); onBack() }
    BackHandler(onBack = back)

    val listState = rememberLazyListState()
    // Фокус на новую строку: она появляется на экране не сразу (список
    // ленивый), а запрос фокуса до этого молча теряется — и следующая
    // фамилия дописалась бы к предыдущей. Поэтому: прокрутить к строке и
    // ждать кадр за кадром, пока её поле не будет готово.
    LaunchedEffect(focusKey) {
        val k = focusKey ?: return@LaunchedEffect
        val idx = ed.workers.indexOfFirst { it.key == k }
        val item = 1 + idx                       // 0-й элемент списка — шапка с полями бригады
        if (idx >= 0 && listState.layoutInfo.visibleItemsInfo.none { it.index == item }) {
            // Строка над новой — для контекста, но не шапка: она выше экрана и
            // вытолкнула бы новую строку за край (так фамилия и уходила не туда).
            listState.scrollToItem(if (item - 1 >= 1) item - 1 else item)
        }
        repeat(30) {
            withFrameNanos { }
            val r = focus[k]
            if (r != null && runCatching { r.requestFocus() }.isSuccess) { focusKey = null; return@LaunchedEffect }
        }
        focusKey = null
    }
    LaunchedEffect(ed.gone) {
        if (ed.gone) { snack.showSnackbar("Бригаду удалили (возможно, на другом телефоне)"); onBack() }
    }

    fun addWorker(text: String = "", at: Int? = null) {
        val row = WorkerRow(ed.newKey(), text)
        if (at == null) ed.workers.add(row) else ed.workers.add(at.coerceIn(0, ed.workers.size), row)
        if (text.isNotEmpty()) ed.changed()
        if (text.isEmpty()) focusKey = row.key
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snack) },
        topBar = {
            TopAppBar(
                navigationIcon = { IconButton(back) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Назад") } },
                title = { Text(ed.name.ifBlank { "Бригада" }, maxLines = 1) },
                actions = { IconButton({ confirmDelete = true }) { Icon(Icons.Filled.Delete, "Удалить бригаду", tint = C.muted2) } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = C.bg2),
            )
        },
    ) { pad ->
        if (!ed.loaded) return@Scaffold
        val active = job?.active == true
        LazyColumn(
            state = listState,
            modifier = Modifier.padding(pad).fillMaxSize().imePadding(),
            contentPadding = PaddingValues(start = 14.dp, end = 14.dp, top = 12.dp, bottom = 40.dp),
        ) {
            item {
                Card {
                    Field("Название бригады")
                    OutlinedTextField(ed.name, { ed.name = it; ed.changed() }, singleLine = true,
                        modifier = Modifier.fillMaxWidth(), colors = fieldColors(),
                        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences))

                    Field("Подрядная организация", hint = "— сохраняется")
                    OutlinedTextField(ed.company, { ed.company = it; invalid = invalid - "company"; ed.changed() },
                        singleLine = true, isError = "company" in invalid, placeholder = { Text("Например: ТОО «СтройМонтаж»") },
                        modifier = Modifier.fillMaxWidth(), colors = fieldColors())

                    Field("Тип работ", hint = "(вопрос 3)")
                    Segments(listOf("montazh" to "Монтаж лесов", "demontazh" to "Демонтаж лесов"), ed.task) {
                        ed.task = it; ed.changed()
                    }
                    Field("Объект", hint = "(вопрос 5)")
                    Segments(listOf("sulphide_1" to "Сульфид 1", "sulphide_2" to "Сульфид 2"), ed.objectKey) {
                        ed.objectKey = it; ed.changed()
                    }

                    Field("Наименование рабочего места", required = true)
                    OutlinedTextField(ed.workplace, { ed.updateWorkplace(it); invalid = invalid - "workplace" },
                        minLines = 2, isError = "workplace" in invalid, placeholder = { Text("Где именно проводятся работы") },
                        modifier = Modifier.fillMaxWidth(), colors = fieldColors(),
                        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences))
                    val recent = Repo.prefs.recentWorkplaces(id).filter { it != ed.workplace.trim() }
                    if (recent.isNotEmpty()) {
                        FlowRow(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            recent.forEach { v ->
                                Text(v, fontSize = 13.sp, color = C.muted, maxLines = 1,
                                    modifier = Modifier.widthIn(max = 280.dp)
                                        .background(C.bg2, RoundedCornerShape(16.dp))
                                        .border(1.dp, C.line, RoundedCornerShape(16.dp))
                                        .clickable { ed.updateWorkplace(v); invalid = invalid - "workplace" }
                                        .padding(horizontal = 11.dp, vertical = 7.dp))
                            }
                        }
                    }
                    Text("Меняется каждый раз. Хранится только на этом телефоне.", fontSize = 11.sp, color = C.muted2,
                        modifier = Modifier.padding(top = 4.dp, start = 2.dp))

                    Spacer(Modifier.height(14.dp))
                    Field("Бригада", hint = "(ФИО — общие для всех бригадиров)")
                }
            }
            itemsIndexed(ed.workers, key = { _, r -> r.key }) { i, row ->
                val req = focus.getOrPut(row.key) { FocusRequester() }
                Row(Modifier.fillMaxWidth().background(C.card).padding(horizontal = 14.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Text("${i + 1}.", color = C.muted2, fontSize = 13.sp, modifier = Modifier.width(26.dp))
                    val dup = ed.isDuplicateAt(i)
                    // К следующему ФИО, а с последнего заполненного — новая строка:
                    // бригадир вводит фамилии подряд, не дотягиваясь до «Добавить».
                    val next = {
                        val idx = ed.workers.indexOfFirst { it.key == row.key }
                        if (idx in 0 until ed.workers.lastIndex) focusKey = ed.workers[idx + 1].key
                        else if (ed.workers.getOrNull(idx)?.text?.isNotBlank() == true) addWorker()
                    }
                    // Курсор — в конце: при переходе «Далее» к заполненной строке
                    // новые буквы не должны вставать перед фамилией.
                    var tfv by remember(row.key) { mutableStateOf(TextFieldValue(row.text, TextRange(row.text.length))) }
                    if (tfv.text != row.text) tfv = TextFieldValue(row.text, TextRange(row.text.length))
                    OutlinedTextField(
                        tfv,
                        { v ->
                            tfv = v
                            val idx = ed.workers.indexOfFirst { it.key == row.key }
                            if (idx >= 0 && ed.workers[idx].text != v.text) { ed.workers[idx] = row.copy(text = v.text); ed.changed() }
                        },
                        singleLine = true, placeholder = { Text("Фамилия Имя Отчество") },
                        isError = dup, supportingText = if (dup) ({ Text("Уже есть выше — форма уйдёт дважды", color = C.warn) }) else null,
                        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Next, autoCorrectEnabled = false),
                        keyboardActions = KeyboardActions(onNext = { next() }),
                        // Enter с внешней клавиатуры — то же, что «Далее» на экранной.
                        modifier = Modifier.weight(1f).focusRequester(req).onPreviewKeyEvent { e ->
                            if (e.key != Key.Enter && e.key != Key.NumPadEnter) return@onPreviewKeyEvent false
                            if (e.type == KeyEventType.KeyDown) next()
                            true
                        },
                        colors = fieldColors(),
                    )
                    IconButton({
                        val idx = ed.workers.indexOfFirst { it.key == row.key }
                        if (idx < 0) return@IconButton
                        ed.workers.removeAt(idx); ed.changed()
                        if (row.text.isNotBlank()) scope.launch {
                            val r = snack.showSnackbar("Удалён: ${row.text.trim()}", "Вернуть", duration = SnackbarDuration.Short)
                            if (r == SnackbarResult.ActionPerformed) addWorker(row.text, idx)
                        }
                    }) { Icon(Icons.Filled.Close, "Удалить работника", tint = C.muted2) }
                }
            }
            item {
                Card(top = false) {
                    OutlinedButton({ addWorker() }, Modifier.fillMaxWidth()) { Text("+ Добавить работника") }
                    PlanPreview(ed)
                    Spacer(Modifier.height(14.dp))
                    Button(
                        onClick = {
                            val bad = buildSet {
                                if (ed.company.isBlank()) add("company")
                                if (ed.workplace.isBlank()) add("workplace")
                            }
                            invalid = bad
                            when {
                                "company" in bad -> scope.launch { snack.showSnackbar("Укажите подрядную организацию") }
                                "workplace" in bad -> scope.launch { snack.showSnackbar("Укажите наименование рабочего места") }
                                ed.names.isEmpty() -> scope.launch { snack.showSnackbar("В бригаде нет ни одного ФИО") }
                                else -> confirmSubmit = true
                            }
                        },
                        enabled = !active,
                        colors = ButtonDefaults.buttonColors(containerColor = C.accent),
                        modifier = Modifier.fillMaxWidth().height(52.dp),
                    ) {
                        Text(when {
                            job?.status == "queued" -> "В очереди…"
                            active -> "Идёт отправка… ${job?.done}/${job?.total}"
                            else -> "Отправить за всю бригаду"
                        }, fontSize = 16.sp)
                    }
                    JobPanel(job, entity?.serverJob,
                        onRetry = { names -> scope.launch { enqueue(ctx, ed, id, entity?.serverId, names) } },
                        onStop = { job?.let { SubmitService.requestStop(it.id) }
                            scope.launch { snack.showSnackbar("Остановится после текущей формы") } })
                }
            }
        }
    }

    if (confirmSubmit) {
        val serverBusy = entity?.serverJob?.let { (it.status == "running" || it.status == "queued") && it.id != job?.id } == true
        SubmitDialog(
            names = ed.names, duplicates = ed.duplicates(), busyElsewhere = serverBusy,
            summary = listOf("Тип работ" to taskLabel(ed.task), "Объект" to objLabel(ed.objectKey),
                "Подрядчик" to ed.company.trim(), "Рабочее место" to ed.workplace.trim()),
            onDismiss = { confirmSubmit = false },
            onOk = {
                confirmSubmit = false
                ed.flushNow()
                scope.launch { enqueue(ctx, ed, id, entity?.serverId, ed.names) }
            },
        )
    }
    if (confirmDelete) {
        ConfirmDialog(
            title = "Удалить бригаду?",
            message = "«${ed.name}» и все её ФИО будут удалены — и у других бригадиров тоже.",
            ok = "Удалить", danger = true,
            onDismiss = { confirmDelete = false },
            onOk = { confirmDelete = false; scope.launch { Repo.deleteBrigade(id); onBack() } },
        )
    }
}

private suspend fun enqueue(ctx: android.content.Context, ed: BrigadeEditor, id: String, serverId: Int?, names: List<String>) {
    val wp = ed.workplace.trim()
    Repo.prefs.addRecentWorkplace(id, wp)
    SubmitService.enqueue(ctx, JobRequest(
        brigadeId = id, serverBlockId = serverId, brigadeName = ed.name.trim(),
        names = names, workplace = wp, task = ed.task, objectKey = ed.objectKey, company = ed.company.trim(),
    ))
}

// ---------- мелкие элементы ----------

@Composable
private fun Card(top: Boolean = true, content: @Composable () -> Unit) {
    val shape = if (top) RoundedCornerShape(topStart = 14.dp, topEnd = 14.dp) else RoundedCornerShape(bottomStart = 14.dp, bottomEnd = 14.dp)
    Column(Modifier.fillMaxWidth().background(C.card, shape).padding(start = 14.dp, end = 14.dp, top = if (top) 4.dp else 6.dp, bottom = if (top) 0.dp else 16.dp)) {
        content()
    }
}

@Composable
private fun Field(label: String, hint: String = "", required: Boolean = false) {
    Row(Modifier.padding(top = 13.dp, bottom = 5.dp, start = 2.dp)) {
        Text(label, fontSize = 12.sp, color = C.muted, fontWeight = FontWeight.Medium)
        if (required) Text(" *", fontSize = 12.sp, color = C.danger)
        if (hint.isNotEmpty()) Text(" $hint", fontSize = 12.sp, color = C.muted2)
    }
}

@Composable
fun fieldColors() = OutlinedTextFieldDefaults.colors(
    focusedContainerColor = C.bg2, unfocusedContainerColor = C.bg2, errorContainerColor = C.bg2,
    unfocusedBorderColor = C.line, focusedBorderColor = C.blue,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun Segments(options: List<Pair<String, String>>, value: String, onPick: (String) -> Unit) {
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        options.forEachIndexed { i, (k, label) ->
            SegmentedButton(
                selected = value == k, onClick = { onPick(k) },
                shape = SegmentedButtonDefaults.itemShape(i, options.size),
                colors = SegmentedButtonDefaults.colors(activeContainerColor = C.blue, activeContentColor = androidx.compose.ui.graphics.Color.White,
                    inactiveContainerColor = C.bg2, inactiveContentColor = C.muted),
            ) { Text(label, maxLines = 1) }
        }
    }
}

/** «Что программа впишет в форму» — тот же сценарий, с которым движок сверяет записанное. */
@Composable
private fun PlanPreview(ed: BrigadeEditor) {
    var open by remember { mutableStateOf(false) }
    val total = remember { Plan.build("", "", "", "", "").sumOf { it.items.size } }
    Column(Modifier.padding(top = 14.dp).fillMaxWidth().background(C.bg2, RoundedCornerShape(12.dp))
        .border(1.dp, C.line, RoundedCornerShape(12.dp))) {
        Row(Modifier.fillMaxWidth().clickable { open = !open }.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Icon(if (open) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown, null, tint = C.muted2, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
            Text("Что программа впишет в форму", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            Text("$total ${plural(total, "поле", "поля", "полей")}", fontSize = 11.5.sp, color = C.muted2)
        }
        if (open) {
            val names = ed.names
            val fio = when (names.size) { 0 -> ""; 1 -> names[0]; else -> "ФИО каждого работника (${names.size}): " + names.joinToString(", ") }
            val plan = Plan.resolve(fio, ed.workplace.trim(), ed.task, ed.objectKey, ed.company.trim())
            Column(Modifier.padding(start = 14.dp, end = 14.dp, bottom = 14.dp)) {
                plan.forEach { page ->
                    Text(page.section.uppercase(), fontSize = 11.sp, color = C.muted2, fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(top = 14.dp, bottom = 6.dp))
                    page.items.forEach { item ->
                        PlanRow(item.q, item.value, item.note)
                    }
                }
                Text("Формы отправляются по одной на каждого работника — меняется только ФИО, остальные ${total - 1} " +
                    "${plural(total - 1, "поле", "поля", "полей")} одинаковы.", fontSize = 11.5.sp, color = C.muted2,
                    modifier = Modifier.padding(top = 10.dp))
            }
        }
    }
}

@Composable
fun PlanRow(q: String, value: String, note: String, empty: String = "— не заполнено —") {
    var expanded by remember { mutableStateOf(false) }
    val long = value.length > 150
    Column(Modifier.fillMaxWidth().padding(vertical = 7.dp)) {
        Text(q, fontSize = 12.5.sp, color = C.muted)
        Text(if (value.isEmpty()) empty else value, fontSize = 14.sp,
            color = if (value.isEmpty()) C.danger else C.text,
            fontStyle = if (value.isEmpty()) FontStyle.Italic else FontStyle.Normal,
            maxLines = if (long && !expanded) 3 else Int.MAX_VALUE, modifier = Modifier.padding(top = 3.dp))
        if (long) Text(if (expanded) "свернуть" else "показать полностью", fontSize = 12.sp, color = C.blue,
            fontWeight = FontWeight.SemiBold, modifier = Modifier.clickable { expanded = !expanded }.padding(top = 3.dp))
        if (note.isNotEmpty()) Text(note, fontSize = 11.5.sp, color = C.muted2, modifier = Modifier.padding(top = 2.dp))
    }
}
