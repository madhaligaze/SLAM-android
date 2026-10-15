package kz.slam.app

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.util.Base64
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kz.slam.app.data.Repo
import kz.slam.app.jobs.JobRequest
import kz.slam.app.jobs.SubmitService
import kz.slam.app.ui.BrigadeScreen
import kz.slam.app.ui.ListScreen
import kz.slam.app.ui.SettingsDialog
import kz.slam.app.ui.SlamTheme

class MainActivity : ComponentActivity() {
    /** Бригада, которую надо открыть (нажали уведомление об отправке). */
    private val openRequest = MutableStateFlow<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (Build.VERSION.SDK_INT >= 33) requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        handleIntent(intent)
        setContent {
            SlamTheme {
                var openId by rememberSaveable { mutableStateOf<String?>(null) }
                var settings by rememberSaveable { mutableStateOf(false) }
                val requested by openRequest.collectAsStateWithLifecycle()
                LaunchedEffect(requested) { requested?.let { openId = it; openRequest.value = null } }

                // Пока приложение на экране — держим бригады свежими (их правят и другие бригадиры).
                val owner = LocalLifecycleOwner.current
                LaunchedEffect(owner) {
                    owner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                        while (true) { Repo.syncNow(); delay(30_000) }
                    }
                }

                val id = openId
                if (id == null) ListScreen(onOpen = { openId = it }, onSettings = { settings = true })
                else BrigadeScreen(id, onBack = { openId = null })
                if (settings) SettingsDialog { settings = false }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(i: Intent?) {
        i?.getStringExtra(EXTRA_BRIGADE)?.let { openRequest.value = it }
        if (BuildConfig.DEBUG) i?.getStringExtra("server")?.let { Repo.prefs.server = it; Repo.requestSync(0) }
        if (BuildConfig.DEBUG && i?.getBooleanExtra("autostart", false) == true) {
            i.removeExtra("autostart")
            testRun(i)
        }
    }

    /**
     * Запуск движка из adb для автотестов (только отладочная сборка): tools/engine_test.sh.
     * Кириллица через adb из Windows доезжает битой — текст передаётся в base64 (суффикс 64).
     */
    private fun testRun(i: Intent) {
        fun str(key: String): String? = i.getStringExtra(key + "64")
            ?.let { String(Base64.decode(it, Base64.DEFAULT), Charsets.UTF_8) } ?: i.getStringExtra(key)
        lifecycleScope.launch {
            SubmitService.enqueue(this@MainActivity, JobRequest(
                brigadeId = "adb-test", serverBlockId = i.getIntExtra("server_block", -1).takeIf { it > 0 },
                brigadeName = str("brigade") ?: "ТЕСТ",
                names = (str("names") ?: "ТЕСТ Приложение").split("|").map { it.trim() }.filter { it.isNotEmpty() },
                workplace = str("workplace") ?: "ТЕСТ — разработка приложения",
                task = i.getStringExtra("task") ?: "montazh",
                objectKey = i.getStringExtra("object") ?: "sulphide_1",
                company = str("company") ?: "ТЕСТ",
                submit = i.getBooleanExtra("submit", false),
            ))
        }
    }

    companion object {
        const val EXTRA_BRIGADE = "brigade_id"
    }
}
