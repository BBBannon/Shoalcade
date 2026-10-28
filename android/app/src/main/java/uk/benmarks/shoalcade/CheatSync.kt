package uk.benmarks.shoalcade

import java.io.IOException

/**
 * Keeps one game's [CheatBook] in step with Jellyfin (D143). Plain Kotlin, so the JVM tests run exactly this code.
 *
 * Jellyfin is the source of truth for the list; changes made on the phone are never lost: each one stays pending in
 * the book until the server has it. The book is read fresh through [book] after every call, so changes made on the
 * phone while a call is out are kept (all calls and book changes run on one thread: the activity's main thread).
 * Sending again is safe: adding the same code twice gives back the one already there.
 */
class CheatSyncCore(
    private val client: JellyfinClient,
    private val ensureCompatible: suspend () -> Unit = {},
) {
    /** The book, read and written by the caller (who also keeps it on the phone). */
    interface Holder {
        var book: CheatBook
    }

    sealed class Result {
        object Done : Result()
        /** The server has no cheats routes (older plugin): everything stays on the phone. */
        object NotOnServer : Result()
        class Failed(val error: IOException) : Result()
    }

    /** From `/shoalcade/info`, asked once. */
    private var hasCheats: Boolean? = null

    suspend fun sync(itemId: String, h: Holder): Result = try {
        ensureCompatible()
        val on = hasCheats ?: ("cheats" in client.info().features).also { hasCheats = it }
        if (!on) Result.NotOnServer else { send(itemId, h); Result.Done }
    } catch (e: IOException) {
        Result.Failed(e)
    }

    private suspend fun send(itemId: String, h: Holder) {
        // 1. Phone cheats not sent yet. A refusal (list full, or a code the server won't take) leaves it on the phone.
        for (c in h.book.cheats.filter { it.isLocal }) {
            if (h.book.byId(c.id) == null) continue
            try {
                h.book = h.book.added(c, client.addCheat(itemId, c.name, c.code))
            } catch (e: ApiException) {
                if (e.code != 409 && e.code != 422) throw e
            }
        }
        // 2. Renames / new codes.
        for ((id, e) in h.book.edits) {
            try {
                client.editCheat(itemId, id, e.name, e.code)
                h.book = h.book.editSent(id, e)
            } catch (x: ApiException) {
                when (x.code) {
                    404 -> h.book = h.book.editGone(id) // deleted elsewhere: the phone's version is sent as a new cheat next time
                    422 -> h.book = h.book.editSent(id, e) // refused: Jellyfin's version wins below
                    else -> throw x
                }
            }
        }
        // 3. Deletes (already gone is fine).
        for (id in h.book.deletes) {
            try {
                client.deleteCheat(itemId, id)
            } catch (x: ApiException) {
                if (x.code != 404) throw x
            }
            h.book = h.book.deleteSent(id)
        }
        // 4. The active list, if changed on the phone; else read Jellyfin's.
        val doc = if (h.book.activeDirty) {
            val sent = h.book.active
            client.setActiveCheats(itemId, sent.filterNot { it.startsWith(CheatBook.LOCAL) }).also { h.book = h.book.activeSent(sent) }
        } else client.cheats(itemId)
        h.book = h.book.merge(doc)
    }
}
