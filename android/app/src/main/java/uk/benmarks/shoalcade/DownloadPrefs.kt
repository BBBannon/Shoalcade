package uk.benmarks.shoalcade

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities

/**
 * D147: "Download games on Wi-Fi only" (off unless switched on), for the whole app. Kept on the phone in the plain
 * SharedPreferences file `display` (key `wifiOnly`, no secrets), like Haptics. Game saves still sync on mobile data:
 * they're small, and saves are precious.
 */
internal object DownloadPrefs {
    private const val KEY = "wifiOnly"

    fun wifiOnly(store: SyncStore): Boolean = store.get(KEY) == "1"
    fun setWifiOnly(store: SyncStore, on: Boolean) = store.set(KEY, if (on) "1" else null)
    fun wifiOnly(ctx: Context): Boolean = wifiOnly(GameSound.store(ctx))

    /** On Wi-Fi or a wired network right now (not mobile data). */
    fun onWifi(ctx: Context): Boolean {
        val cm = ctx.getSystemService(ConnectivityManager::class.java) ?: return false
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) || caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
    }

    /** Why a download can't start now, or null if it can. */
    fun blocked(wifiOnly: Boolean, onWifi: Boolean): String? =
        if (wifiOnly && !onWifi) "Downloads are set to Wi-Fi only. Connect to Wi-Fi, or change it in Settings." else null

    /** The question asked before a folder game (RPG Maker) downloads: its size, and a warning if it may not work. */
    fun folderQuestion(name: String, list: FolderList, have: Long, onWifi: Boolean): String = buildString {
        val need = (list.total - have).coerceAtLeast(0)
        append("$name is ${FolderDownload.sizeText(list.total)}")
        if (have > 0 && need == 0L) append(" (nothing new to download)")
        else if (have > 0 && need < list.total) append(" (about ${FolderDownload.sizeText(need)} still to download)")
        append(".")
        if (!onWifi) append(" You're not on Wi-Fi, so this uses mobile data.")
        if (list.mayNotWork) append("\n\nThis game comes with Windows add-ons that may not work here. It may not start.")
    }
}
