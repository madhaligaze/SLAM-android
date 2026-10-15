package kz.slam.app.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import kz.slam.app.data.BrigadeEntity
import kz.slam.app.data.JobEntity
import kz.slam.app.data.Repo

/** Главный экран: список бригад. Долгое нажатие — выбор для удаления. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ListScreen(onOpen: (String) -> Unit, onSettings: () -> Unit) {
    val brigades by Repo.brigades.collectAsStateWithLifecycle(initialValue = null)
    val jobs by Repo.jobs.collectAsStateWithLifecycle(initialValue = emptyList())
    val sync by Repo.sync.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val snack = remember { SnackbarHostState() }
    val selected = remember { mutableStateListOf<String>() }
    var creating by rememberSaveable { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    val latestByBrigade = remember(jobs) { jobs.groupBy { it.brigadeId }.mapValues { it.value.first() } }

    Scaffold(
        snackbarHost = { SnackbarHost(snack) },
        topBar = {
            if (selected.isNotEmpty()) {
                TopAppBar(
                    title = { Text("Выбрано: ${selected.size}") },
                    navigationIcon = { IconButton({ selected.clear() }) { Icon(Icons.Filled.Close, "Отменить выбор") } },
                    actions = {
                        Button({ confirmDelete = true }, colors = androidx.compose.material3.ButtonDefaults.buttonColors(containerColor = C.danger)) {
                            Icon(Icons.Filled.Delete, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Удалить")
                        }
                        Spacer(Modifier.width(8.dp))
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = C.card),
                )
            } else {
                TopAppBar(
                    title = {
                        Column {
                            Text("SLAM", fontWeight = FontWeight.SemiBold)
                            Text(syncLine(sync), fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                color = if (sync.online == false) C.warn else C.muted2)
                        }
                    },
                    actions = {
                        Button({ creating = true }) { Text("+ Бригада") }
                        Box {
                            IconButton({ menu = true }) { Icon(Icons.Filled.MoreVert, "Меню") }
                            DropdownMenu(menu, { menu = false }) {
                                DropdownMenuItem({ Text("Синхронизировать") }, {
                                    menu = false
                                    scope.launch { if (!Repo.syncNow()) snack.showSnackbar(Repo.sync.value.message) }
                                })
                                DropdownMenuItem({ Text("Настройки") }, { menu = false; onSettings() })
                            }
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = C.bg2),
                )
            }
        },
    ) { pad ->
        PullToRefreshBox(
            isRefreshing = sync.running,
            onRefresh = { scope.launch { if (!Repo.syncNow()) snack.showSnackbar(Repo.sync.value.message) } },
            modifier = Modifier.padding(pad).fillMaxSize(),
        ) {
            val list = brigades
            when {
                list == null -> {}
                list.isEmpty() -> EmptyState(sync.running)
                else -> LazyColumn(contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.fillMaxSize()) {
                    items(list, key = { it.id }) { b ->
                        BrigadeRow(
                            b, latestByBrigade[b.id], selecting = selected.isNotEmpty(), selected = b.id in selected,
                            onClick = {
                                if (selected.isNotEmpty()) { if (b.id in selected) selected.remove(b.id) else selected.add(b.id) }
                                else onOpen(b.id)
                            },
                            onLongClick = { if (b.id !in selected) selected.add(b.id) },
                        )
                    }
                }
            }
        }
    }

    if (creating) {
        NameDialog(
            title = "Новая бригада", label = "Название бригады", initial = "Бригада", ok = "Создать",
            onDismiss = { creating = false },
            onOk = { name -> creating = false; scope.launch { onOpen(Repo.createBrigade(name)) } },
        )
    }
    if (confirmDelete) {
        val n = selected.size
        ConfirmDialog(
            title = "Удалить $n ${plural(n, "бригаду", "бригады", "бригад")}?",
            message = "Выбранные бригады и их ФИО будут удалены — и у других бригадиров тоже.",
            ok = "Удалить $n", danger = true,
            onDismiss = { confirmDelete = false },
            onOk = {
                confirmDelete = false
                val ids = selected.toList(); selected.clear()
                scope.launch { ids.forEach { Repo.deleteBrigade(it) }; snack.showSnackbar("Удалено: ${ids.size}") }
            },
        )
    }
    androidx.activity.compose.BackHandler(enabled = selected.isNotEmpty()) { selected.clear() }
}

fun syncLine(s: kz.slam.app.data.SyncState): String = when {
    s.running -> "Синхронизация…"
    s.online == true -> "Общие бригады · обновлено ${fmtWhen(s.at)}"
    s.online == false -> "Нет связи с сервером — данные на телефоне"
    else -> "оценка рисков · заполнение за бригаду"
}

@Composable
private fun EmptyState(loading: Boolean) {
    LazyColumn(Modifier.fillMaxSize()) {   // прокручиваемый — иначе не работает «потянуть, чтобы обновить»
        item {
            Column(Modifier.fillMaxWidth().padding(top = 96.dp, start = 24.dp, end = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally) {
                Text(if (loading) "Загрузка бригад…" else "Пока нет ни одной бригады.\nНажмите «+ Бригада», чтобы создать первую.",
                    color = C.muted2, textAlign = TextAlign.Center)
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun BrigadeRow(b: BrigadeEntity, job: JobEntity?, selecting: Boolean, selected: Boolean,
                       onClick: () -> Unit, onLongClick: () -> Unit) {
    val haptic = LocalHapticFeedback.current
    val n = b.names.size
    val badge = jobBadge(b, job)
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(C.card)
            .border(1.dp, if (selected) C.blue else C.line, RoundedCornerShape(14.dp))
            .combinedClickable(onClick = onClick, onLongClick = {
                haptic.performHapticFeedback(HapticFeedbackType.LongPress); onLongClick()
            })
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (selecting) {
            Box(Modifier.size(24.dp).clip(CircleShape).background(if (selected) C.blue else C.bg2)
                .border(2.dp, if (selected) C.blue else C.line2, CircleShape), contentAlignment = Alignment.Center) {
                if (selected) Icon(Icons.Filled.Check, null, tint = Color.White, modifier = Modifier.size(15.dp))
            }
            Spacer(Modifier.width(12.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(b.name.ifBlank { "Без названия" }, fontSize = 16.sp, fontWeight = FontWeight.SemiBold,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("$n ${plural(n, "работник", "работника", "работников")} · ${objShort(b.objectKey)} · ${taskShort(b.task)}",
                fontSize = 13.sp, color = C.muted2, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (badge != null) JobBadgeLine(badge, Modifier.padding(top = 6.dp))
            if (b.dirty) Text("• ещё не на сервере — уйдёт при появлении связи", fontSize = 11.sp, color = C.muted2,
                modifier = Modifier.padding(top = 4.dp))
        }
        if (!selecting) Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = C.muted2)
    }
}

@Composable
fun JobBadgeLine(badge: JobBadge, modifier: Modifier = Modifier) {
    val color = when (badge.tone) {
        Tone.OK -> C.accent; Tone.WARN -> C.warn; Tone.BAD -> C.danger; Tone.RUN -> Color(0xFF4A8BE6); Tone.MUTED -> C.muted2
    }
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        StatusDot(badge.tone)
        Spacer(Modifier.width(6.dp))
        Text(badge.text, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = color,
            maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
fun StatusDot(tone: Tone) {
    val color = when (tone) {
        Tone.OK -> C.accent; Tone.WARN -> C.warn; Tone.BAD -> C.danger; Tone.RUN -> Color(0xFF4A8BE6); Tone.MUTED -> C.muted2
    }
    if (tone == Tone.RUN) {
        androidx.compose.material3.CircularProgressIndicator(Modifier.size(12.dp), color = color, strokeWidth = 2.dp)
    } else {
        Box(Modifier.size(8.dp).clip(CircleShape).background(color))
    }
}
