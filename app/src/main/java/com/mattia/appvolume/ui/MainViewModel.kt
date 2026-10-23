package com.mattia.appvolume.ui

import android.app.Application
import android.content.pm.PackageManager
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.mattia.appvolume.AudioNames.TAG
import com.mattia.appvolume.IVolumeService
import com.mattia.appvolume.PlayerInfo
import com.mattia.appvolume.data.AppSetting
import com.mattia.appvolume.data.LevelStore
import com.mattia.appvolume.shizuku.ShizukuConnector
import com.mattia.appvolume.shizuku.ShizukuState
import com.mattia.appvolume.testtone.TestTonePlayer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Un'app con almeno un player, con il livello salvato. */
data class AppAudio(
    val uid: Int,
    val packageName: String,
    val label: String,
    val players: List<PlayerInfo>,
    val setting: AppSetting,
)

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val packageManager = application.packageManager
    private val store = LevelStore(application)
    private val connector = ShizukuConnector().also { it.start() }
    private val testTone = TestTonePlayer()
    private val labelCache = HashMap<String, String>()

    val shizukuState: StateFlow<ShizukuState> = connector.state

    private val players = MutableStateFlow<List<PlayerInfo>>(emptyList())

    private val _log = MutableStateFlow<List<String>>(emptyList())
    val log: StateFlow<List<String>> = _log.asStateFlow()

    private val _serviceUid = MutableStateFlow<Int?>(null)
    val serviceUid: StateFlow<Int?> = _serviceUid.asStateFlow()

    private val _testToneRunning = MutableStateFlow(false)
    val testToneRunning: StateFlow<Boolean> = _testToneRunning.asStateFlow()

    private val settings = store.settings.stateIn(viewModelScope, SharingStarted.Eagerly, emptyMap())

    val apps: StateFlow<List<AppAudio>> = combine(players, settings) { players, settings ->
        players.groupBy { it.uid }.map { (uid, list) ->
            val pkg = packageKey(list.first().packageName)
            AppAudio(uid, pkg, labelFor(pkg), list, settings[pkg] ?: AppSetting())
        }.sortedBy { it.label.lowercase() }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    init {
        // Ogni volta che lo UserService si (ri)collega gli rimandiamo i livelli salvati.
        viewModelScope.launch {
            var lastService: IVolumeService? = null
            connector.state.collect { state ->
                val service = (state as? ShizukuState.Ready)?.service
                if (service != null && service !== lastService) {
                    launch(Dispatchers.IO) { pushSavedSettings(service) }
                }
                lastService = service
            }
        }
        viewModelScope.launch(Dispatchers.IO) {
            while (true) {
                poll()
                delay(POLL_MS)
            }
        }
    }

    fun requestPermission() = connector.requestPermission()

    fun restartService() = connector.restartService()

    fun setLevel(app: AppAudio, level: Int, persist: Boolean) {
        val setting = app.setting.copy(level = level)
        callService { it.setLevel(app.uid, level) }
        if (persist) viewModelScope.launch { store.save(app.packageName, setting) }
    }

    fun toggleMute(app: AppAudio) {
        val setting = app.setting.copy(muted = !app.setting.muted)
        callService { it.setMute(app.uid, setting.muted) }
        viewModelScope.launch { store.save(app.packageName, setting) }
    }

    fun clearLog() = callService { it.clearLog() }

    fun toggleTestTone(recreate: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            if (testTone.isRunning) testTone.stop() else testTone.start(recreate)
            _testToneRunning.value = testTone.isRunning
        }
    }

    override fun onCleared() {
        testTone.stop()
        connector.stop()
    }

    private suspend fun pushSavedSettings(service: IVolumeService) {
        val saved = store.settings.first()
        for ((pkg, setting) in saved) {
            val uid = try {
                packageManager.getPackageUid(pkg, 0)
            } catch (e: PackageManager.NameNotFoundException) {
                Log.w(TAG, "Livello salvato per $pkg ma il package non esiste più")
                continue
            }
            try {
                service.setLevel(uid, setting.level)
                service.setMute(uid, setting.muted)
                Log.i(TAG, "Riapplicato livello salvato: $pkg uid=$uid $setting")
            } catch (t: Throwable) {
                Log.e(TAG, "Errore nel riapplicare il livello di $pkg", t)
            }
        }
    }

    private fun poll() {
        val service = (connector.state.value as? ShizukuState.Ready)?.service
        if (service == null) {
            players.value = emptyList()
            _serviceUid.value = null
            return
        }
        try {
            players.value = service.players
            _log.value = service.log
            _serviceUid.value = service.serviceUid
        } catch (t: Throwable) {
            Log.e(TAG, "Errore leggendo lo stato dallo UserService", t)
        }
    }

    private fun callService(block: (IVolumeService) -> Unit) {
        val service = (connector.state.value as? ShizukuState.Ready)?.service ?: return
        viewModelScope.launch(Dispatchers.IO) {
            try {
                block(service)
            } catch (t: Throwable) {
                Log.e(TAG, "Chiamata allo UserService fallita", t)
            }
        }
    }

    /** Per gli UID condivisi il servizio restituisce "pkg1,pkg2": usiamo il primo. */
    private fun packageKey(packages: String) = packages.substringBefore(',')

    private fun labelFor(pkg: String): String = synchronized(labelCache) {
        labelCache.getOrPut(pkg) {
            try {
                packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
            } catch (e: PackageManager.NameNotFoundException) {
                pkg
            }
        }
    }

    private companion object {
        const val POLL_MS = 1_000L
    }
}
