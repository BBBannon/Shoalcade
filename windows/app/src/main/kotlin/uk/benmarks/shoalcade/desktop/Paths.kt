package uk.benmarks.shoalcade.desktop

import uk.benmarks.shoalcade.BuildConfig
import java.io.File

/**
 * Where Shoalcade for Windows keeps its files, per Windows user (plan §4.4):
 *
 * ```
 * %LOCALAPPDATA%\Shoalcade\          (Dev build: Shoalcade Dev\, so the two never share games, saves or login: D152)
 *   session.bin    the login, encrypted with Windows DPAPI for this Windows user (TokenStore)
 *   games.json     the last game list, for offline use (as the phone's files/games.json)
 *   prefs\         small settings files (sort, toggles, play time queue)
 *   art\           box art, art\<itemId>-<tag>.jpg (as the phone's files/art)
 *   library\       the phone's Storage.kt layout, unchanged: roms\<console>\<itemId>\<file>, saves\<user>\…,
 *                  memstick\<user>\<itemId>\…, states\<user>\…, cheats\<user>\…
 *   cores\  system\  logs\
 * ```
 *
 * On Linux (development and tests only) the same layout lives under `$XDG_DATA_HOME/Shoalcade` (default
 * `~/.local/share/Shoalcade`). [dataDir] is the phone's `filesDir`: the shared Storage.kt puts `library\` under it.
 */
class Paths(val dataDir: File) {
    val sessionFile get() = File(dataDir, "session.bin")
    val gameListFile get() = File(dataDir, "games.json")
    val artDir get() = File(dataDir, "art")
    val libraryDir get() = File(dataDir, "library")
    val coresDir get() = File(dataDir, "cores")
    val systemDir get() = File(dataDir, "system")
    val logsDir get() = File(dataDir, "logs")

    companion object {
        /** The folder name: "Shoalcade", or "Shoalcade Dev" for the Dev build. */
        fun appFolder(dev: Boolean) = if (dev) "Shoalcade Dev" else "Shoalcade"

        /**
         * The data folder for this computer's user. [os] = `os.name`, [env] = environment, [home] = `user.home`.
         * Windows with no LOCALAPPDATA (never seen in practice) falls back to the standard `<home>\AppData\Local`.
         */
        fun dataDirFor(dev: Boolean, os: String, env: Map<String, String>, home: String): File {
            val name = appFolder(dev)
            return if (isWindows(os)) {
                val local = env["LOCALAPPDATA"]?.takeIf { it.isNotBlank() } ?: File(home, "AppData\\Local").path
                File(local, name)
            } else {
                val xdg = env["XDG_DATA_HOME"]?.takeIf { it.isNotBlank() && it.startsWith("/") } ?: File(home, ".local/share").path
                File(xdg, name)
            }
        }

        fun isWindows(os: String = System.getProperty("os.name") ?: "") = os.startsWith("Windows", ignoreCase = true)

        /** This build's folder for this user. */
        fun forThisUser(dev: Boolean = BuildConfig.DEBUG): Paths =
            Paths(dataDirFor(dev, System.getProperty("os.name") ?: "", System.getenv(), System.getProperty("user.home") ?: "."))
    }
}
