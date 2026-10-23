package com.mattia.appvolume.shizuku

import android.content.ComponentName
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import android.util.Log
import com.mattia.appvolume.AudioNames.TAG
import com.mattia.appvolume.BuildConfig
import com.mattia.appvolume.IVolumeService
import com.mattia.appvolume.service.VolumeUserService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import rikka.shizuku.Shizuku

sealed interface ShizukuState {
    data object NotRunning : ShizukuState
    data object PermissionMissing : ShizukuState
    /** Permesso ok; [service] è null finché lo UserService non è collegato. */
    data class Ready(val shizukuUid: Int, val service: IVolumeService?) : ShizukuState
}

/** Gestisce permesso Shizuku e collegamento allo UserService. */
class ShizukuConnector {

    private val _state = MutableStateFlow<ShizukuState>(ShizukuState.NotRunning)
    val state: StateFlow<ShizukuState> = _state.asStateFlow()

    private var service: IVolumeService? = null
    private var binding = false

    private val serviceArgs = Shizuku.UserServiceArgs(
        ComponentName(BuildConfig.APPLICATION_ID, VolumeUserService::class.java.name),
    )
        // Daemon: il servizio resta vivo anche quando l'app viene chiusa.
        .daemon(true)
        .processNameSuffix("volume")
        .debuggable(BuildConfig.DEBUG)
        .version(BuildConfig.VERSION_CODE)

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder?) {
            if (binder == null || !binder.pingBinder()) {
                Log.e(TAG, "UserService collegato ma binder non valido")
                return
            }
            Log.i(TAG, "UserService collegato")
            binding = false
            service = IVolumeService.Stub.asInterface(binder)
            refresh()
        }

        override fun onServiceDisconnected(name: ComponentName) {
            Log.w(TAG, "UserService scollegato")
            binding = false
            service = null
            refresh()
        }
    }

    private val binderReceived = Shizuku.OnBinderReceivedListener { refresh() }
    private val binderDead = Shizuku.OnBinderDeadListener {
        binding = false
        service = null
        refresh()
    }
    private val permissionResult = Shizuku.OnRequestPermissionResultListener { _, _ -> refresh() }

    fun start() {
        Shizuku.addBinderReceivedListenerSticky(binderReceived)
        Shizuku.addBinderDeadListener(binderDead)
        Shizuku.addRequestPermissionResultListener(permissionResult)
    }

    fun stop() {
        Shizuku.removeBinderReceivedListener(binderReceived)
        Shizuku.removeBinderDeadListener(binderDead)
        Shizuku.removeRequestPermissionResultListener(permissionResult)
        // Non distruggiamo il servizio: è un daemon e deve continuare ad applicare i volumi.
        runCatching { Shizuku.unbindUserService(serviceArgs, connection, false) }
    }

    fun requestPermission() {
        if (Shizuku.pingBinder()) Shizuku.requestPermission(REQUEST_CODE)
    }

    /** Ferma lo UserService e lo riavvia (utile per i test). */
    fun restartService() {
        runCatching { Shizuku.unbindUserService(serviceArgs, connection, true) }
            .onFailure { Log.e(TAG, "unbindUserService fallito", it) }
        binding = false
        service = null
        refresh()
    }

    private fun refresh() {
        _state.value = when {
            !Shizuku.pingBinder() -> ShizukuState.NotRunning
            Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED ->
                ShizukuState.PermissionMissing
            else -> {
                if (service == null) bind()
                ShizukuState.Ready(Shizuku.getUid(), service)
            }
        }
    }

    private fun bind() {
        if (binding) return
        binding = true
        try {
            Shizuku.bindUserService(serviceArgs, connection)
        } catch (t: Throwable) {
            Log.e(TAG, "bindUserService fallito", t)
            binding = false
        }
    }

    private companion object {
        const val REQUEST_CODE = 1
    }
}
