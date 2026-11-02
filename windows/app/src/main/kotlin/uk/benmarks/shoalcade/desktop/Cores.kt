package uk.benmarks.shoalcade.desktop

import uk.benmarks.shoalcade.Console
import java.io.File

/**
 * Which emulator core plays each console on this computer, from `windows/cores/cores.lock` (written by
 * tools/build_windows_cores.sh, built at the phone's pinned commits, D190). The lock is packed into the app, so the
 * core file names and the snapshot tag come from the one place that says what was built.
 *
 * The snapshot tag ([tag]) is `<target>-<core>-<first 7 of the commit>`, e.g. `win-mgba-7a12d6d`: a server snapshot is
 * only ever loaded by a core with exactly that tag (plan §6, the browser's own rule). A Linux development build gets
 * `linux-…`, so its snapshots never go into the Windows build.
 */
class Cores(private val rows: List<Row>, val target: String,
            /** Settings → PS2 → PS2 emulator (D-3), asked each time: LRPS2 (default) or PCEE2. */
            private val ps2: () -> Ps2.Emulator = { Ps2.Emulator.LRPS2 }) {
    data class Row(val core: String, val target: String, val commit: String, val file: String, val sha256: String)

    /** The lock's name for the core that plays [c] (link play: [linkCoreName]). */
    fun coreName(c: Console): String? = when (c) {
        Console.GB, Console.GBC, Console.GBA -> "mgba"
        Console.NDS -> "melonds"
        Console.PSX -> "pcsx"
        Console.N64 -> "mupen"
        Console.PSP -> "ppsspp"
        Console.N3DS -> "azahar"
        Console.GC, Console.WII -> "dolphin" // Windows only (D218)
        Console.PS2 -> ps2().core // "lrps2" (pcsx2_libretro.dll) or "pcee2": each has its own snapshot tag
    }

    /** Link play's core (the phone's LinkRules.GPSP_CORE for GBA: mGBA has no link; the DS keeps melonDS DS). */
    fun linkCoreName(c: Console): String? = when (c) {
        Console.GBA -> "gpsp"
        Console.NDS -> "melonds"
        else -> null
    }

    fun row(c: Console, link: Boolean = false): Row? =
        (if (link) linkCoreName(c) else coreName(c))?.let { n -> rows.firstOrNull { it.core == n && it.target == target } }

    /** The core library's file name for [c] on this computer (`mgba_libretro.dll`, …); [link] = link play's. */
    fun file(c: Console, link: Boolean = false): String? = row(c, link)?.file

    /** The snapshot tag for [c] (≤ 32 characters, letters, digits and `-`, as the server keeps it). */
    fun tag(c: Console): String? = row(c)?.let { "${it.target}-${it.core}-${it.commit.take(7)}" }

    companion object {
        fun parse(text: String, target: String, ps2: () -> Ps2.Emulator = { Ps2.Emulator.LRPS2 }): Cores = Cores(text.lineSequence().map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }
            .mapNotNull { l -> l.split('\t').takeIf { it.size >= 8 }?.let { Row(it[0], it[1], it[3], it[5], it[7]) } }.toList(), target, ps2)

        /** "win" on Windows, "linux" elsewhere (development and tests). */
        fun thisTarget() = if (Paths.isWindows()) "win" else "linux"

        /** The lock packed into the app (resource `cores.lock`). */
        fun packed(ps2: () -> Ps2.Emulator = { Ps2.Emulator.LRPS2 }): Cores = parse(Cores::class.java.getResource("/cores.lock")?.readText()
            ?: error("cores.lock missing from the app"), thisTarget(), ps2)
    }
}

/**
 * Where the player program and the core libraries are. The installer puts them beside the app (`player\`,
 * `cores\`); development and tests point at their own builds with `-Dshoalcade.player=` / `-Dshoalcade.cores=` (or the
 * `SHOALCADE_PLAYER` / `SHOALCADE_CORES` environment). [systemDir] is the per-user system folder (PPSSPP's assets go in
 * `system\PPSSPP`, plan §4.4).
 */
data class PlayerSetup(val player: File, val coresDir: File, val systemDir: File, val cores: Cores) {
    fun core(c: Console, link: Boolean = false): File? = cores.file(c, link)?.let { File(coresDir, it) }?.takeIf { it.isFile }

    companion object {
        fun find(paths: Paths, cores: Cores = Cores.packed()): PlayerSetup {
            val appDir = System.getProperty("compose.application.resources.dir")?.let { File(it).parentFile }
            fun pick(prop: String, env: String, fallback: String): File =
                (System.getProperty(prop) ?: System.getenv(env))?.let(::File) ?: File(appDir ?: File("."), fallback)
            val exe = if (Paths.isWindows()) "ShoalcadePlayer.exe" else "ShoalcadePlayer"
            return PlayerSetup(pick("shoalcade.player", "SHOALCADE_PLAYER", "player/$exe"),
                pick("shoalcade.cores", "SHOALCADE_CORES", "cores"), paths.systemDir, cores)
        }
    }
}
