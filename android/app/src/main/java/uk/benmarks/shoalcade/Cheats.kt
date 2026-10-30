package uk.benmarks.shoalcade

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException

/**
 * Cheats (D143), phone side. Pure Kotlin: the rules the server uses for names and codes ([CheatRules]), one game's cheat
 * list as kept on the phone ([CheatBook]), the file it lives in ([CheatStore]) and the order cheats go into the core
 * ([CheatApply]). [CheatSyncCore] keeps the book in step with Jellyfin; [GameActivity] shows and applies it.
 */
object CheatRules {
    /** The server's limits (CheatStore.MaxName / MaxCode / MaxLines). */
    const val MAX_NAME = 60
    const val MAX_CODE = 4000
    const val MAX_LINES = 64

    private fun allowed(c: Char) = c in 'A'..'Z' || c in 'a'..'z' || c in '0'..'9' || c in " \n+-:_.,"

    /** A name as the server keeps it: trimmed, runs of spaces made single. Null if empty, too long or has control characters. */
    fun cleanName(raw: String): String? {
        val s = raw.split(' ', '\t', '\n', '\r').filter { it.isNotEmpty() }.joinToString(" ")
        return s.takeIf { it.isNotEmpty() && it.length <= MAX_NAME && it.none(Char::isISOControl) }
    }

    /** A code as the server keeps it: each line trimmed with single spaces, blank lines dropped, lines joined with "\n". */
    fun tidyCode(raw: String): String =
        raw.replace("\r\n", "\n").replace('\r', '\n').split('\n')
            .map { l -> l.split(' ', '\t').filter { it.isNotEmpty() }.joinToString(" ") }
            .filter { it.isNotEmpty() }.joinToString("\n")

    /** The tidy code, or null if the server would refuse it. */
    fun cleanCode(raw: String): String? {
        val s = tidyCode(raw)
        return s.takeIf { it.isNotEmpty() && it.length <= MAX_CODE && s.count { c -> c == '\n' } + 1 <= MAX_LINES && it.all(::allowed) }
    }

    private val N64_CODE = Regex("^[0-9A-Fa-f]{8} [0-9A-Fa-f]{4}$")
    private val N64_RUN = Regex("^[0-9A-Fa-f]{12}$")

    /**
     * The code as it should be kept for [console]. N64 (plan-n64 Step 0): the core splits a GameShark code at any
     * non-hex character, so `8XXXXXXXYYYY` with no space would be one number that silently does nothing. Each such line
     * (or "+"-joined part) gets its space put in; anything else is left for [problem] to judge.
     */
    fun normalise(console: Console?, code: String): String {
        if (console != Console.N64) return code
        return tidyCode(code).split('\n').joinToString("\n") { line ->
            line.split('+').joinToString("+") { p -> p.trim().let { if (N64_RUN.matches(it)) it.substring(0, 8) + " " + it.substring(8) else it } }
        }
    }

    /** Plain-English reason the name or code can't be used, or null if both are fine. [console]'s own code format is checked too (N64). */
    fun problem(name: String, code: String, console: Console? = null): String? {
        val n = name.split(' ', '\t').filter { it.isNotEmpty() }.joinToString(" ")
        val c = tidyCode(normalise(console, code))
        val bad = c.firstOrNull { !allowed(it) }
        val n64Bad = console == Console.N64 && c.isNotEmpty() &&
            c.split('\n').flatMap { it.split('+') }.any { !N64_CODE.matches(it.trim()) }
        return when {
            n.isEmpty() -> "Give the cheat a name."
            n.length > MAX_NAME -> "Names can be up to $MAX_NAME characters."
            n.any(Char::isISOControl) -> "The name has a character that can't be used."
            c.isEmpty() -> "Type the cheat code."
            bad != null -> "Codes use only letters, numbers, spaces and + - : _ . , so \"$bad\" can't be used."
            c.length > MAX_CODE -> "That code is too long (up to $MAX_CODE characters)."
            c.count { it == '\n' } + 1 > MAX_LINES -> "That code has too many lines (up to $MAX_LINES)."
            n64Bad -> "N64 codes look like 8XXXXXXX YYYY: 8 characters, a space, then 4. One code per line."
            else -> null
        }
    }

    /** The same code, ignoring spaces, line breaks, "+" and letter case (the server's CodeKey): spots a cheat added twice. */
    fun key(code: String): String = code.filterNot { it == ' ' || it == '\n' || it == '+' || it == '\r' || it == '\t' }.uppercase()

    /** What the core is given: the lines joined with "+" (the libretro convention; the server keeps "\n"). */
    fun forCore(code: String): String = tidyCode(code).replace('\n', '+')

    /** Which codes work, per console (tested on the phone cores 28/10/2026, `tools/testbed/cheat_cores.py`). */
    fun hint(console: Console): String = when (console) {
        Console.GB, Console.GBC -> "Game Boy: GameShark codes, 8 characters each, like 01xxxxxx. One code per line."
        Console.GBA -> "Game Boy Advance: CodeBreaker, GameShark or Action Replay codes. One code per line, as written."
        Console.NDS -> "DS: Action Replay codes, two blocks of 8 per line, like 02xxxxxx yyyyyyyy."
        Console.PSX -> "PlayStation: GameShark codes, like 8xxxxxxx yyyy. One code per line."
        Console.PSP -> "PSP: CWCheat codes, one per line, like _L 0x2xxxxxxx 0xyyyyyyyy."
        Console.N64 -> "Nintendo 64: GameShark codes, like 8XXXXXXX YYYY. One code per line."
        Console.N3DS -> "3DS games can't be played on the phone yet."
    }
}

/** One cheat. [id] is the server's (12 hex) or, until it has been sent, a phone one starting [CheatBook.LOCAL]. */
data class Cheat(val id: String, val name: String, val code: String) {
    val isLocal get() = id.startsWith(CheatBook.LOCAL)
}

/** A cheat as a snapshot remembers it (no id: ids differ between phone and server). */
data class SnapCheat(val name: String, val code: String)

/** A name / code change not yet sent to Jellyfin. */
data class CheatEdit(val name: String, val code: String)

/**
 * One game's cheats on the phone, for one user. Offline first: every change is made here straight away and kept as
 * pending ([Cheat.isLocal] adds, [edits], [deletes], [activeDirty]) until [CheatSyncCore] has sent it.
 * [active] = the ids switched on, in order (the in-game save's list). [backedUp] = the save was backed up before the
 * first time cheats were switched on for this game. Immutable: every change returns a new book.
 */
data class CheatBook(
    val cheats: List<Cheat> = emptyList(),
    val active: List<String> = emptyList(),
    val activeDirty: Boolean = false,
    val edits: Map<String, CheatEdit> = emptyMap(),
    val deletes: Set<String> = emptySet(),
    val backedUp: Boolean = false,
) {
    fun byId(id: String) = cheats.firstOrNull { it.id == id }
    fun isOn(id: String) = id in active
    /** The cheats switched on, in the order they go into the core. */
    fun activeCheats(): List<Cheat> = active.mapNotNull(::byId)
    /** Something is waiting to be sent to Jellyfin. */
    val pending get() = activeDirty || edits.isNotEmpty() || deletes.isNotEmpty() || cheats.any { it.isLocal }

    /** Adds a cheat (name and code tidied; null if they can't be used). Same code as one already here: that one, unchanged. */
    fun add(name: String, code: String, newId: () -> String = ::localId): Pair<CheatBook, Cheat>? {
        val n = CheatRules.cleanName(name) ?: return null
        val c = CheatRules.cleanCode(code) ?: return null
        cheats.firstOrNull { CheatRules.key(it.code) == CheatRules.key(c) }?.let { return this to it }
        val cheat = Cheat(newId(), n, c)
        return copy(cheats = cheats + cheat) to cheat
    }

    /** Renames a cheat and/or changes its code. Null if they can't be used or the cheat is gone. */
    fun edit(id: String, name: String, code: String): CheatBook? {
        val old = byId(id) ?: return null
        val n = CheatRules.cleanName(name) ?: return null
        val c = CheatRules.cleanCode(code) ?: return null
        if (old.name == n && old.code == c) return this
        val list = cheats.map { if (it.id == id) it.copy(name = n, code = c) else it }
        return copy(cheats = list, edits = if (old.isLocal) edits else edits + (id to CheatEdit(n, c)))
    }

    fun delete(id: String): CheatBook {
        val old = byId(id) ?: return this
        return copy(cheats = cheats - old, active = active - id, activeDirty = activeDirty || id in active,
            edits = edits - id, deletes = if (old.isLocal) deletes else deletes + id)
    }

    fun setOn(id: String, on: Boolean): CheatBook = when {
        byId(id) == null || on == isOn(id) -> this
        on -> copy(active = active + id, activeDirty = true)
        else -> copy(active = active - id, activeDirty = true)
    }

    /**
     * Loading a snapshot (D143): exactly its cheats go on, matched by code; any missing from the list are added.
     * Returns the new book and how many were added.
     */
    fun fromSnapshot(list: List<SnapCheat>): Pair<CheatBook, Int> {
        var b = this
        var added = 0
        val ids = mutableListOf<String>()
        for (s in list) {
            val before = b.cheats.size
            val (nb, c) = b.add(s.name, s.code) ?: continue
            if (nb.cheats.size > before) added++
            b = nb
            if (c.id !in ids) ids += c.id
        }
        return b.copy(active = ids, activeDirty = b.activeDirty || ids != active) to added
    }

    fun snapshotCheats(): List<SnapCheat> = activeCheats().map { SnapCheat(it.name, it.code) }

    // ---- results from the server (see CheatSyncCore) ----

    /**
     * [sent] (a phone cheat) is now on Jellyfin as [server]. Its id changes everywhere. If it was changed or deleted
     * on the phone while the call was out, that change is kept as pending for the new id.
     */
    fun added(sent: Cheat, server: ServerCheat): CheatBook {
        val now = byId(sent.id)
        if (now == null) { // deleted while the call was out: delete it on the server too
            return copy(deletes = deletes + server.id)
        }
        val existing = byId(server.id) // the server already had this code (a dedupe): merge into that one
        val changed = now.name != sent.name || now.code != sent.code
        val renamed = now.copy(id = server.id, name = if (changed) now.name else server.name, code = if (changed) now.code else server.code)
        val list = if (existing != null) cheats.filter { it.id != sent.id } else cheats.map { if (it.id == sent.id) renamed else it }
        val act = active.map { if (it == sent.id) server.id else it }.distinct()
        return copy(cheats = list, active = act, edits = if (changed && existing == null) edits + (server.id to CheatEdit(now.name, now.code)) else edits)
    }

    /** An edit reached the server: forgotten unless it changed again while the call was out. */
    fun editSent(id: String, sent: CheatEdit) = if (edits[id] == sent) copy(edits = edits - id) else this

    /** The server no longer has a cheat the phone changed: the phone's version becomes a new phone cheat. */
    fun editGone(id: String, newId: () -> String = ::localId): CheatBook {
        val c = byId(id) ?: return copy(edits = edits - id)
        val n = newId()
        return copy(cheats = cheats.map { if (it.id == id) it.copy(id = n) else it },
            active = active.map { if (it == id) n else it }, edits = edits - id)
    }

    fun deleteSent(id: String) = copy(deletes = deletes - id)

    /** The active list reached the server: clean again, unless it changed while the call was out or still has phone ids. */
    fun activeSent(sent: List<String>) =
        if (active == sent && active.none { it.startsWith(LOCAL) }) copy(activeDirty = false) else this

    /**
     * Takes Jellyfin's list: server cheats (with any pending edits on top, minus pending deletes), then the phone cheats
     * not sent yet. The active list is the server's unless the phone has a change not sent yet.
     */
    fun merge(doc: CheatDoc): CheatBook {
        val server = doc.cheats.filter { it.id !in deletes }.map { s ->
            edits[s.id]?.let { Cheat(s.id, it.name, it.code) } ?: Cheat(s.id, s.name, s.code)
        }
        val ids = server.map { it.id }.toSet()
        val local = cheats.filter { it.isLocal }
        val all = server + local
        val allIds = all.map { it.id }.toSet()
        val act = if (activeDirty) active.filter { it in allIds } else doc.active.filter { it in ids }.distinct()
        return copy(cheats = all, active = act, edits = edits.filterKeys { it in ids })
    }

    companion object {
        const val LOCAL = "phone-"
        fun localId() = LOCAL + java.util.UUID.randomUUID().toString().replace("-", "").take(16)

        fun toJson(b: CheatBook): JSONObject = JSONObject().put("v", 1)
            .put("cheats", JSONArray(b.cheats.map { JSONObject().put("id", it.id).put("name", it.name).put("code", it.code) }))
            .put("active", JSONArray(b.active))
            .put("activeDirty", b.activeDirty)
            .put("edits", JSONObject().apply { b.edits.forEach { (k, e) -> put(k, JSONObject().put("name", e.name).put("code", e.code)) } })
            .put("deletes", JSONArray(b.deletes.toList()))
            .put("backedUp", b.backedUp)

        /** Throws (JSONException) on a damaged file. */
        fun fromJson(o: JSONObject): CheatBook {
            val cs = o.getJSONArray("cheats")
            val cheats = (0 until cs.length()).map { i -> cs.getJSONObject(i).let { Cheat(it.getString("id"), it.getString("name"), it.getString("code")) } }
            val ids = cheats.map { it.id }.toSet()
            val act = o.optJSONArray("active") ?: JSONArray()
            val ed = o.optJSONObject("edits") ?: JSONObject()
            val del = o.optJSONArray("deletes") ?: JSONArray()
            return CheatBook(
                cheats = cheats,
                active = (0 until act.length()).map { act.getString(it) }.filter { it in ids }.distinct(),
                activeDirty = o.optBoolean("activeDirty"),
                edits = ed.keys().asSequence().associateWith { k -> ed.getJSONObject(k).let { CheatEdit(it.getString("name"), it.getString("code")) } },
                deletes = (0 until del.length()).map { del.getString(it) }.toSet(),
                backedUp = o.optBoolean("backedUp"),
            )
        }

        fun snapToJson(list: List<SnapCheat>): String =
            JSONArray(list.map { JSONObject().put("name", it.name).put("code", it.code) }).toString()

        /** A snapshot's cheats; empty if none were on (or the file is damaged: then no cheats is the safe choice). */
        fun snapFromJson(text: String?): List<SnapCheat> = runCatching {
            val a = JSONArray(text ?: return emptyList())
            (0 until a.length()).map { a.getJSONObject(it) }.map { SnapCheat(it.getString("name"), it.getString("code")) }
        }.getOrDefault(emptyList())
    }
}

/**
 * Where a game's [CheatBook] lives: `library/cheats/<userId>/<itemId>.json`. A file that can't be read is moved aside
 * (`.damaged-<time>`), never deleted, and an empty book starts.
 */
object CheatStore {
    fun file(userId: String, gameId: String) = File(Storage.root, "cheats/$userId/$gameId.json")

    /** The book, and whether a damaged file had to be moved aside. */
    fun read(userId: String, gameId: String): Pair<CheatBook, Boolean> {
        val f = file(userId, gameId)
        if (!f.isFile) return CheatBook() to false
        return runCatching { CheatBook.fromJson(JSONObject(f.readText())) to false }.getOrElse {
            val aside = File(f.parentFile, "${f.name}.damaged-${System.currentTimeMillis()}")
            if (!f.renameTo(aside)) f.copyTo(aside, overwrite = true)
            CheatBook() to true
        }
    }

    fun write(userId: String, gameId: String, book: CheatBook) {
        try {
            Storage.writeText(file(userId, gameId), CheatBook.toJson(book).toString())
        } catch (e: Exception) {
            throw if (e is IOException) e else IOException("Couldn't keep the cheats on the phone.", e)
        }
    }
}

/** What goes into the core, in order. Always a reset first, then every ticked cheat from index 0 (mGBA ignores "off"). */
object CheatApply {
    sealed class Op {
        object Reset : Op()
        data class Set(val index: Int, val code: String) : Op()
    }

    fun ops(active: List<Cheat>): List<Op> = listOf(Op.Reset) + active.mapIndexed { i, c -> Op.Set(i, CheatRules.forCore(c.code)) }
}
