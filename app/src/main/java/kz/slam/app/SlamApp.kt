package kz.slam.app

import android.app.Application
import android.net.ConnectivityManager
import android.net.Network
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kz.slam.app.data.Repo
import kz.slam.app.jobs.SubmitService

class SlamApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Repo.init(this)
        // Процесс только что поднялся — значит, служба отправки сейчас не идёт,
        // и «идущие» отправки в базе — оборванные вместе с прошлым процессом.
        // Синхронно: новая отправка не должна попасть под эту разметку.
        runBlocking(Dispatchers.IO) { Repo.recoverInterrupted() }
        SubmitService.ensureChannel(this)
        // Появилась сеть — сразу досылаем правки и отметки, накопленные без неё.
        getSystemService(ConnectivityManager::class.java)
            .registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) = Repo.requestSync(500)
            })
    }
}
