package uk.benmarks.shoalcade

import android.content.Context
import android.content.SharedPreferences

/**
 * L18: the save-sync state file is renamed from dev 2's `"jgsync"` to `"scsync"`. App 0.6.0-dev2 is already on
 * Ben's phone with real state in `"jgsync"` (last server save version + SHA-256 per game, unsettled conflicts,
 * saved-after-snapshot marks, orphans already mentioned). Losing it would make the app think it never synced, so it
 * is copied across once, safely:
 *
 *  1. If `"scsync"` holds [MARKER], the copy is finished: nothing to do.
 *  2. Otherwise `"scsync"` is made an exact copy of `"jgsync"` in one atomic write (SharedPreferences `commit`).
 *     Without the marker, nothing but this copy ever writes `"scsync"` (see [open]), so anything already there can
 *     only be a leftover of an unfinished copy; the old file is the truth.
 *  3. The copy is read back and compared with the old file.
 *  4. Only then is [MARKER] written (a second atomic write). Stopped between any two steps, the next start does it
 *     again from step 2 with the same result.
 *
 * If any write fails or the check doesn't match, this run keeps using `"jgsync"` exactly as dev 2 did (nothing is
 * lost; the copy is tried again next start). `"jgsync"` is never changed or deleted: it stays as a backup.
 */
object SyncRename {
    const val OLD = "jgsync"
    const val NEW = "scsync"
    /** Written into [NEW] once the copy is checked. SyncStore keys are "b|", "c|", "s|", "o|", so no clash. */
    const val MARKER = "m|from-jgsync"

    enum class Outcome { ALREADY_DONE, NOTHING_TO_COPY, COPIED, FAILED }

    /** One SharedPreferences file, as far as the copy needs it (a map in tests). */
    interface PrefsFile {
        fun all(): Map<String, Any?>
        /** Replace the whole contents in one atomic write; false if it couldn't be written. */
        fun replaceAll(values: Map<String, Any?>): Boolean
        fun putString(key: String, value: String): Boolean
    }

    fun migrate(old: PrefsFile, new: PrefsFile): Outcome {
        if (new.all()[MARKER] != null) return Outcome.ALREADY_DONE
        val src = old.all().filterValues { it != null }
        if (!new.replaceAll(src)) return Outcome.FAILED
        if (new.all() != src) return Outcome.FAILED
        if (!new.putString(MARKER, "1")) return Outcome.FAILED
        return if (src.isEmpty()) Outcome.NOTHING_TO_COPY else Outcome.COPIED
    }

    @Volatile private var chosen: String? = null

    /** The sync-state prefs to use, after the one-time copy. Every [SyncStore] on sync state must open it here. */
    fun open(ctx: Context): SharedPreferences {
        val app = ctx.applicationContext
        val name = chosen ?: synchronized(this) {
            chosen ?: run {
                val outcome = runCatching {
                    migrate(Android(app.getSharedPreferences(OLD, Context.MODE_PRIVATE)),
                        Android(app.getSharedPreferences(NEW, Context.MODE_PRIVATE)))
                }.getOrDefault(Outcome.FAILED)
                (if (outcome == Outcome.FAILED) OLD else NEW).also { chosen = it }
            }
        }
        return app.getSharedPreferences(name, Context.MODE_PRIVATE)
    }

    private class Android(private val p: SharedPreferences) : PrefsFile {
        override fun all(): Map<String, Any?> = HashMap(p.all)
        override fun replaceAll(values: Map<String, Any?>): Boolean {
            val e = p.edit().clear()
            for ((k, v) in values) when (v) {
                is String -> e.putString(k, v)
                is Int -> e.putInt(k, v)
                is Long -> e.putLong(k, v)
                is Float -> e.putFloat(k, v)
                is Boolean -> e.putBoolean(k, v)
                is Set<*> -> e.putStringSet(k, v.filterIsInstance<String>().toSet())
                else -> return false
            }
            return e.commit()
        }
        override fun putString(key: String, value: String) = p.edit().putString(key, value).commit()
    }
}
