package uk.benmarks.shoalcade

/**
 * D90: only saves made in the game sync. A snapshot (save state) also holds the game's save as it was when the
 * snapshot was taken, and loading it can put that old copy in the emulator's memory (melonDS does). The app writes
 * the emulator's save to the phone every 30 s and on pause, and the phone save is what gets uploaded, so without
 * this the snapshot's copy would quietly become "the save". The rule (same as the browser player, play.js):
 *  - [begin] when a snapshot load starts: from then nothing counts as a save until the baseline is taken;
 *  - [setBase] with the save as it is just after the load (null = the game has no save yet);
 *  - [isGameSave] true only once the game itself has written a save that differs from that baseline
 *    (a change only in the Game Boy / GBA clock footer doesn't count);
 *  - [clear] once that save is on the phone: back to the normal rule; [cancel] undoes a [begin] whose load didn't
 *    happen. The upload's "saved after loading a snapshot" mark is set by GameActivity while [active] (the phone
 *    keeps it until the upload); [after] (true for the rest of the session) is only read by the tests (L16).
 * Plain Kotlin, so the JVM tests run exactly this code.
 */
class SnapshotGuard {
    var active = false
        private set
    var waiting = false
        private set
    var after = false
        private set
    private var base: ByteArray? = null

    /** How the guard was before [begin], so a load that didn't happen can be undone exactly ([cancel]). */
    class Prior(val active: Boolean = false, val waiting: Boolean = false, val after: Boolean = false, val base: ByteArray? = null)

    @Synchronized fun begin(): Prior {
        val prior = Prior(active, waiting, after, base)
        active = true; waiting = true; base = null; after = true
        return prior
    }

    /** The load didn't happen (nothing in memory changed): back to exactly how it was. */
    @Synchronized fun cancel(prior: Prior) {
        active = prior.active; waiting = prior.waiting; base = prior.base; after = prior.after
    }

    @Synchronized fun setBase(bytes: ByteArray?) { base = bytes?.copyOf(); waiting = false }

    @Synchronized fun isGameSave(bytes: ByteArray?): Boolean = when {
        !active -> true
        waiting -> false
        else -> bytes != null && meaningfulChange(bytes, base)
    }

    @Synchronized fun clear() { active = false; waiting = false; base = null }

    @Synchronized fun reset() { clear(); after = false }

    companion object {
        /**
         * True if the save changed in a way worth keeping (same rule as the browser's meaningfulChange). Saves are a
         * multiple of 512 bytes plus an optional clock footer (up to 64 bytes); if only the footer changed, the game
         * didn't save, the clock just ticked.
         */
        fun meaningfulChange(now: ByteArray, before: ByteArray?): Boolean {
            if (before == null || now.size != before.size) return true
            var i = 0
            while (i < now.size && now[i] == before[i]) i++
            if (i == now.size) return false
            val footer = now.size % 512
            return !(footer in 1..64 && i >= now.size - footer)
        }
    }
}
