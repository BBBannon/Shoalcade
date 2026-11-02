package uk.benmarks.shoalcade.desktop

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Auto upload for folder saves (D222 D-6; plan §14.2): PSP, 3DS, GameCube, Wii and PS2 games write their own save
 * files into the game's folder, so there is no `saveChanged` from the player to start an upload. This looks at the
 * save files ([stamp]: each one's name, size and time) every [pollMs]; once they have changed and then stayed the same
 * for [quietMs] (about 5 s: the emulator has finished writing), [onSettled] runs once for that change. A change while
 * waiting starts the wait again (debounce). The upload itself is the session's usual one (the phone's SavePlan rule:
 * backup, version check, both changed asks), so nothing new decides about a save here.
 *
 * Polling, not the Windows folder events: it sees the same thing on every file system, never misses a change made
 * while it was busy, and costs one folder listing a second.
 */
class FolderWatcher(
    private val scope: CoroutineScope,
    private val stamp: () -> String?,
    private val onSettled: suspend () -> Unit,
    private val quietMs: Long = QUIET_MS,
    private val pollMs: Long = 1_000,
) {
    private var job: Job? = null

    fun start() {
        if (job != null) return
        job = scope.launch {
            var settled = runCatching { stamp() }.getOrNull() // the files as the game started: not a change
            var last = settled
            var quietFor = 0L
            while (isActive) {
                delay(pollMs)
                val now = runCatching { stamp() }.getOrNull()
                if (now != last) { last = now; quietFor = 0; continue }
                if (now == settled) continue
                quietFor += pollMs
                if (quietFor >= quietMs) {
                    settled = now
                    quietFor = 0
                    runCatching { onSettled() }.onFailure { Log.w("Folder save upload after a change failed", it) }
                }
            }
        }
    }

    fun stop() { job?.cancel(); job = null }

    companion object {
        /** D-6: about 5 s after the save folder stops changing. */
        const val QUIET_MS = 5_000L
    }
}
