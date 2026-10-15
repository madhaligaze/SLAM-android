package kz.slam.app.data

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import kotlinx.serialization.encodeToString
import kz.slam.app.BuildConfig

/**
 * Настройки и то, что живёт только на этом телефоне.
 *
 * Рабочее место намеренно не уходит на сервер: оно меняется каждую смену.
 * Но и пропадать между открытиями бригады не должно — держим черновик здесь.
 */
class Prefs(context: Context) {
    private val p: SharedPreferences = context.getSharedPreferences("slam", Context.MODE_PRIVATE)

    var server: String
        get() = p.getString("server", null) ?: BuildConfig.DEFAULT_SERVER
        set(v) = p.edit().putString("server", v.trim().trimEnd('/')).apply()

    /** Как телефон подписывается в отметках об отправке (видят другие бригадиры). */
    var device: String
        get() = p.getString("device", null) ?: "${Build.MANUFACTURER} ${Build.MODEL}".trim()
        set(v) = p.edit().putString("device", v.trim()).apply()

    fun workplace(brigadeId: String): String = p.getString("wp.$brigadeId", "") ?: ""
    fun setWorkplace(brigadeId: String, v: String) {
        p.edit().apply { if (v.isEmpty()) remove("wp.$brigadeId") else putString("wp.$brigadeId", v) }.apply()
    }

    /** Последние отправленные рабочие места бригады — подсказки в одно касание. */
    fun recentWorkplaces(brigadeId: String): List<String> =
        p.getString("wpr.$brigadeId", null)?.let { runCatching { json.decodeFromString<List<String>>(it) }.getOrNull() }
            ?: emptyList()

    fun addRecentWorkplace(brigadeId: String, v: String) {
        val list = (listOf(v) + recentWorkplaces(brigadeId).filter { it != v }).take(4)
        p.edit().putString("wpr.$brigadeId", json.encodeToString(list)).apply()
    }

    fun forget(brigadeId: String) {
        p.edit().remove("wp.$brigadeId").remove("wpr.$brigadeId").apply()
    }
}
