package com.mattia.appvolume.service

import android.content.Context
import android.media.AudioManager
import android.media.AudioPlaybackConfiguration
import android.os.Handler
import android.os.HandlerThread
import android.os.Process
import android.os.SystemClock
import android.util.Log
import com.mattia.appvolume.AudioNames
import com.mattia.appvolume.AudioNames.TAG
import com.mattia.appvolume.IVolumeService
import com.mattia.appvolume.PlayerInfo
import org.lsposed.hiddenapibypass.HiddenApiBypass
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CountDownLatch
import kotlin.system.exitProcess

/**
 * UserService Shizuku: gira in un processo separato con identità shell (UID 2000) o root.
 *
 * La shell ha MODIFY_AUDIO_ROUTING, quindi AudioService ci considera un client
 * "privilegiato" e ci passa configurazioni non anonimizzate (UID e PlayerProxy).
 * Shizuku istanzia la classe via reflection passando un Context del nostro package.
 *
 * Tutto lo stato è toccato solo dal thread [handler], così non servono lock.
 */
class VolumeUserService(context: Context) : IVolumeService.Stub() {

    private data class UidSetting(val level: Int = 100, val muted: Boolean = false) {
        val volume: Float get() = if (muted) 0f else level / 100f
    }

    private class TrackedPlayer(
        val piid: Int,
        val uid: Int,
        val pid: Int,
        val packageName: String,
        val type: Int,
        val firstSeenMs: Long,
    ) {
        var state: Int = -1
        var usage: Int = 0
        var config: AudioPlaybackConfiguration? = null
        var appliedVolume: Float = -1f
        var lastResult: String = ""
        var firstApplyDelayMs: Long = -1
    }

    private val thread = HandlerThread("AppVolService").apply { start() }
    private val handler = Handler(thread.looper)
    private val packageManager = context.packageManager
    private val audioManager = context.getSystemService(AudioManager::class.java)

    private val settings = HashMap<Int, UidSetting>()
    private val players = HashMap<Int, TrackedPlayer>()
    private val packageCache = HashMap<Int, String>()
    private val eventLog = ArrayDeque<String>()
    private val timeFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.ROOT)

    private val callback = object : AudioManager.AudioPlaybackCallback() {
        override fun onPlaybackConfigChanged(configs: List<AudioPlaybackConfiguration>) {
            onConfigs(configs)
        }
    }

    init {
        try {
            // In app_process di solito non serve, ma non costa nulla.
            HiddenApiBypass.addHiddenApiExemptions("Landroid/media/")
        } catch (t: Throwable) {
            Log.w(TAG, "HiddenApiBypass non disponibile", t)
        }
        log("Servizio avviato: uid=${Process.myUid()} pid=${Process.myPid()}")
        audioManager.registerAudioPlaybackCallback(callback, handler)
        handler.post { onConfigs(audioManager.activePlaybackConfigurations) }
    }

    // ---- AIDL ----

    override fun destroy() {
        log("destroy(): ripristino i player a 1.0 ed esco")
        runOnHandler {
            audioManager.unregisterAudioPlaybackCallback(callback)
            players.values.filter { it.appliedVolume >= 0f }.forEach { applyVolume(it, 1f, "destroy") }
        }
        exitProcess(0)
    }

    override fun getPlayers(): List<PlayerInfo> = runOnHandler {
        players.values.sortedWith(compareBy({ it.uid }, { it.piid })).map {
            PlayerInfo(
                piid = it.piid,
                uid = it.uid,
                pid = it.pid,
                packageName = it.packageName,
                playerType = it.type,
                playerState = it.state,
                usage = it.usage,
                appliedVolume = it.appliedVolume,
                lastResult = it.lastResult,
                firstApplyDelayMs = it.firstApplyDelayMs,
            )
        }
    }

    override fun setLevel(uid: Int, level: Int) {
        handler.post {
            val old = settings[uid] ?: UidSetting()
            updateSetting(uid, old.copy(level = level.coerceIn(0, 100)), "setLevel")
        }
    }

    override fun setMute(uid: Int, muted: Boolean) {
        handler.post {
            val old = settings[uid] ?: UidSetting()
            updateSetting(uid, old.copy(muted = muted), if (muted) "mute" else "unmute")
        }
    }

    override fun getLog(): List<String> = synchronized(eventLog) { eventLog.toList() }

    override fun clearLog() {
        synchronized(eventLog) { eventLog.clear() }
    }

    override fun getServiceUid(): Int = Process.myUid()

    // ---- Logica ----

    private fun updateSetting(uid: Int, setting: UidSetting, reason: String) {
        if (settings[uid] == setting) return
        settings[uid] = setting
        log("$reason uid=$uid (${packageFor(uid)}) livello=${setting.level} muto=${setting.muted}")
        players.values.filter { it.uid == uid }.forEach { applyVolume(it, setting.volume, reason) }
    }

    private fun onConfigs(configs: List<AudioPlaybackConfiguration>) {
        val now = SystemClock.elapsedRealtime()
        val seen = HashSet<Int>()
        for (apc in configs) {
            val snap = try {
                HiddenPlayerApi.read(apc)
            } catch (t: Throwable) {
                log("Impossibile leggere AudioPlaybackConfiguration: $t", t)
                continue
            }
            seen += snap.piid
            var player = players[snap.piid]
            val isNew = player == null
            if (player == null) {
                player = TrackedPlayer(snap.piid, snap.uid, snap.pid, packageFor(snap.uid), snap.type, now)
                players[snap.piid] = player
                log(
                    "Nuovo player piid=${snap.piid} uid=${snap.uid} pid=${snap.pid} " +
                        "pkg=${player.packageName} tipo=${AudioNames.playerType(snap.type)} " +
                        "stato=${AudioNames.playerState(snap.state)} usage=${apc.audioAttributes.usage}",
                )
            }
            val stateChanged = !isNew && player.state != snap.state
            if (stateChanged) {
                log(
                    "Stato piid=${snap.piid} (${player.packageName}): " +
                        "${AudioNames.playerState(player.state)} -> ${AudioNames.playerState(snap.state)}",
                )
            }
            player.state = snap.state
            player.usage = apc.audioAttributes.usage
            player.config = apc

            val setting = settings[snap.uid] ?: continue
            when {
                isNew -> applyVolume(player, setting.volume, "nuovo player")
                stateChanged -> applyVolume(player, setting.volume, "cambio stato")
                // Riapplica in silenzio: recupera il caso in cui il sistema ha rimesso 1.0
                // (es. PlaybackActivityMonitor.unmutePlayersForCall dopo una chiamata).
                else -> applyVolume(player, setting.volume, "riapplica", quiet = true)
            }
        }
        val gone = players.keys - seen
        for (piid in gone) {
            val p = players.remove(piid) ?: continue
            log("Player rimosso piid=$piid (${p.packageName})")
        }
    }

    private fun applyVolume(player: TrackedPlayer, volume: Float, reason: String, quiet: Boolean = false) {
        val config = player.config ?: return
        if (player.state == 0) return // RELEASED: niente da fare
        val now = SystemClock.elapsedRealtime()
        val error = try {
            HiddenPlayerApi.setVolume(config, volume)
            null
        } catch (t: Throwable) {
            t
        }
        val firstApply = player.firstApplyDelayMs < 0
        if (firstApply) player.firstApplyDelayMs = now - player.firstSeenMs
        player.appliedVolume = volume
        player.lastResult = if (error == null) "ok" else "eccezione: $error"
        if (quiet && error == null) return
        val msg = "setVolume($volume) [$reason] piid=${player.piid} uid=${player.uid} " +
            "pkg=${player.packageName} tipo=${AudioNames.playerType(player.type)} " +
            "stato=${AudioNames.playerState(player.state)} -> ${player.lastResult}" +
            if (firstApply) " (primo setVolume dopo ${player.firstApplyDelayMs} ms)" else ""
        log(msg, error)
    }

    private fun packageFor(uid: Int): String = packageCache.getOrPut(uid) {
        try {
            packageManager.getPackagesForUid(uid)?.joinToString(",") ?: "uid:$uid"
        } catch (t: Throwable) {
            "uid:$uid"
        }
    }

    private fun log(message: String, error: Throwable? = null) {
        if (error != null) Log.e(TAG, message, error) else Log.i(TAG, message)
        val line = "${timeFormat.format(Date())} $message"
        synchronized(eventLog) {
            eventLog.addLast(line)
            while (eventLog.size > MAX_LOG) eventLog.removeFirst()
        }
    }

    /** Esegue [block] sul thread del servizio e aspetta il risultato (chiamate AIDL sincrone). */
    private fun <T> runOnHandler(block: () -> T): T {
        if (thread.looper.isCurrentThread) return block()
        val done = CountDownLatch(1)
        var result: Result<T>? = null
        handler.post {
            result = runCatching(block)
            done.countDown()
        }
        done.await()
        return result!!.getOrThrow()
    }

    private companion object {
        const val MAX_LOG = 300
    }
}
