package com.mattia.appvolume

/**
 * Nomi leggibili delle costanti @SystemApi di AudioPlaybackConfiguration.
 * Valori da media/java/android/media/AudioPlaybackConfiguration.java
 * (LineageOS android_frameworks_base, ramo lineage-24.0 = Android 17).
 */
object AudioNames {
    const val TAG = "AppVol"

    fun playerType(type: Int): String = when (type) {
        -1 -> "UNKNOWN"
        1 -> "AudioTrack"
        2 -> "MediaPlayer"
        3 -> "SoundPool"
        11 -> "OpenSL BufferQueue"
        12 -> "OpenSL URI/FD"
        13 -> "AAudio"
        14 -> "HW_SOURCE"
        15 -> "EXTERNAL_PROXY"
        else -> "tipo $type"
    } + " ($type)"

    fun playerState(state: Int): String = when (state) {
        -1 -> "UNKNOWN"
        0 -> "RELEASED"
        1 -> "IDLE"
        2 -> "STARTED"
        3 -> "PAUSED"
        4 -> "STOPPED"
        else -> "stato $state"
    }
}
