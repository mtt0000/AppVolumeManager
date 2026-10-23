package com.mattia.appvolume

import android.os.Parcel
import android.os.Parcelable

/** Fotografia di un player visto dallo UserService, passata all'app via AIDL. */
data class PlayerInfo(
    val piid: Int,
    val uid: Int,
    val pid: Int,
    val packageName: String,
    val playerType: Int,
    val playerState: Int,
    val usage: Int,
    /** Ultimo moltiplicatore applicato, -1 se non è mai stato toccato. */
    val appliedVolume: Float,
    /** Esito dell'ultimo setVolume: "ok", "eccezione: ..." oppure "" se mai chiamato. */
    val lastResult: String,
    /** Millisecondi tra la prima volta che il player è stato visto e il primo setVolume. */
    val firstApplyDelayMs: Long,
) : Parcelable {

    override fun writeToParcel(dest: Parcel, flags: Int) {
        dest.writeInt(piid)
        dest.writeInt(uid)
        dest.writeInt(pid)
        dest.writeString(packageName)
        dest.writeInt(playerType)
        dest.writeInt(playerState)
        dest.writeInt(usage)
        dest.writeFloat(appliedVolume)
        dest.writeString(lastResult)
        dest.writeLong(firstApplyDelayMs)
    }

    override fun describeContents(): Int = 0

    companion object {
        @JvmField
        val CREATOR = object : Parcelable.Creator<PlayerInfo> {
            override fun createFromParcel(source: Parcel) = PlayerInfo(
                piid = source.readInt(),
                uid = source.readInt(),
                pid = source.readInt(),
                packageName = source.readString().orEmpty(),
                playerType = source.readInt(),
                playerState = source.readInt(),
                usage = source.readInt(),
                appliedVolume = source.readFloat(),
                lastResult = source.readString().orEmpty(),
                firstApplyDelayMs = source.readLong(),
            )

            override fun newArray(size: Int) = arrayOfNulls<PlayerInfo>(size)
        }
    }
}
