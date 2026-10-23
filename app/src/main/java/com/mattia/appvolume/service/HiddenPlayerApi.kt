package com.mattia.appvolume.service

import android.media.AudioPlaybackConfiguration
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method

/**
 * Accesso via reflection ai metodi @SystemApi di AudioPlaybackConfiguration e PlayerProxy.
 *
 * Usiamo la reflection invece di uno stub jar: se una firma cambia in una versione
 * futura di Android lo vediamo nel log (NoSuchMethodException) invece di un crash.
 * Firme verificate su Android 17 (LineageOS lineage-24.0):
 *  - AudioPlaybackConfiguration.getClientUid/getClientPid/getPlayerType/getPlayerState/
 *    getPlayerInterfaceId/getPlayerProxy
 *  - PlayerProxy.setVolume(float) -> IPlayer.setVolume -> PlayerBase.setVolumeMultiplier
 */
object HiddenPlayerApi {

    data class Snapshot(
        val piid: Int,
        val uid: Int,
        val pid: Int,
        val type: Int,
        val state: Int,
    )

    private val apcClass = AudioPlaybackConfiguration::class.java

    private val getClientUid: Method by lazy { apcClass.getMethod("getClientUid") }
    private val getClientPid: Method by lazy { apcClass.getMethod("getClientPid") }
    private val getPlayerType: Method by lazy { apcClass.getMethod("getPlayerType") }
    private val getPlayerState: Method by lazy { apcClass.getMethod("getPlayerState") }
    private val getPlayerInterfaceId: Method by lazy { apcClass.getMethod("getPlayerInterfaceId") }
    private val getPlayerProxy: Method by lazy { apcClass.getMethod("getPlayerProxy") }
    private val proxySetVolume: Method by lazy {
        Class.forName("android.media.PlayerProxy").getMethod("setVolume", Float::class.javaPrimitiveType)
    }

    fun read(apc: AudioPlaybackConfiguration): Snapshot = Snapshot(
        piid = invoke(getPlayerInterfaceId, apc) as Int,
        uid = invoke(getClientUid, apc) as Int,
        pid = invoke(getClientPid, apc) as Int,
        type = invoke(getPlayerType, apc) as Int,
        state = invoke(getPlayerState, apc) as Int,
    )

    /** Lancia l'eccezione originale (non InvocationTargetException) se fallisce. */
    fun setVolume(apc: AudioPlaybackConfiguration, volume: Float) {
        val proxy = invoke(getPlayerProxy, apc)
            ?: throw IllegalStateException("getPlayerProxy() ha restituito null (config anonimizzata?)")
        invoke(proxySetVolume, proxy, volume)
    }

    private fun invoke(method: Method, target: Any, vararg args: Any?): Any? = try {
        method.invoke(target, *args)
    } catch (e: InvocationTargetException) {
        throw e.targetException ?: e
    }
}
