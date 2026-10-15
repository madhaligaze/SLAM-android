package kz.slam.app.engine

import android.annotation.SuppressLint
import android.content.Context
import android.view.View
import android.webkit.CookieManager
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import kotlin.coroutines.resume

/**
 * Невидимый браузер для формы — то, чем на сервере был Playwright.
 *
 * Android WebView — тот же Chromium. Страница формы грузится в него, а
 * помощник slam_engine.js выполняется внутри страницы через
 * evaluateJavascript. Всё общение с WebView — только в главном потоке.
 */
class FormBrowser(private val context: Context) {
    private var web: WebView? = null
    private val engineJs: String by lazy {
        context.assets.open("slam_engine.js").bufferedReader().use { it.readText() }
    }

    @SuppressLint("SetJavaScriptEnabled")
    suspend fun start() = withContext(Dispatchers.Main) {
        if (web != null) return@withContext
        web = WebView(context).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            // Без пометки «wv»: для формы мы обычный мобильный Chrome.
            settings.userAgentString = settings.userAgentString.replace("; wv", "")
            webViewClient = WebViewClient()
            // Окна у WebView нет — задаём размер вручную, иначе страница
            // раскладывается в ширину 0.
            measure(
                View.MeasureSpec.makeMeasureSpec(WIDTH, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(HEIGHT, View.MeasureSpec.EXACTLY),
            )
            layout(0, 0, WIDTH, HEIGHT)
        }
    }

    /** Чистый лист перед каждым работником: MS Forms хранит черновик ответа на устройстве. */
    suspend fun resetSession() = withContext(Dispatchers.Main) {
        CookieManager.getInstance().removeAllCookies(null)
        WebStorage.getInstance().deleteAllData()
        web?.clearHistory()
    }

    suspend fun load(url: String) = withContext(Dispatchers.Main) { requireWeb().loadUrl(url) }

    suspend fun blank() = withContext(Dispatchers.Main) { web?.loadUrl("about:blank") }

    /**
     * Вызвать window.__slam.<fn>(args) внутри страницы. Возвращает
     * JSONObject / JSONArray / String / Number / Boolean или null.
     */
    suspend fun call(fn: String, vararg args: Any?): Any? = withContext(Dispatchers.Main) {
        val argJs = args.joinToString(",") { jsLiteral(it) }
        val script = "(function(){try{$engineJs\nreturn window.__slam.$fn($argJs);}" +
            "catch(e){return {__error:String(e&&e.message||e)};}})()"
        val raw = withTimeout(CALL_TIMEOUT_MS) {
            suspendCancellableCoroutine { cont ->
                requireWeb().evaluateJavascript(script) { cont.resume(it) }
            }
        }
        val value = if (raw == null || raw == "null") null else JSONTokener(raw).nextValue()
        if (value is JSONObject && value.has("__error")) {
            throw IllegalStateException("Ошибка скрипта на странице формы: " + value.optString("__error"))
        }
        if (value == JSONObject.NULL) null else value
    }

    suspend fun stop() = withContext(Dispatchers.Main) {
        web?.apply {
            stopLoading()
            loadUrl("about:blank")
            destroy()
        }
        web = null
    }

    private fun requireWeb(): WebView = web ?: error("Браузер не запущен")

    private fun jsLiteral(v: Any?): String = when (v) {
        null -> "null"
        is String -> JSONObject.quote(v)
        is Number, is Boolean -> v.toString()
        is Map<*, *> -> JSONObject(v).toString()
        is List<*> -> JSONArray(v).toString()
        else -> JSONObject.quote(v.toString())
    }

    companion object {
        const val WIDTH = 1080
        const val HEIGHT = 1920
        const val CALL_TIMEOUT_MS = 15_000L
    }
}
