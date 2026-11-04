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
    private val CBDS_FIRST = Regex("^[08]000[0-9A-Fa-f]{4} ?([0-9A-Fa-f]{8})$")

    /**
     * A CodeBreaker DS code: its first line is `0000xxxx` (encrypted) or `8000xxxx` (not encrypted) followed by the
     * game's 4-character code (letters/digits) in hex, e.g. `8000FD28 41534D45` = "ASME". The DS core only runs Action
     * Replay codes, so such a code would run as the wrong code type. We never convert them: the encrypted ones need key
     * data from the DS BIOS, which Shoalcade doesn't ship or ask for.
     */
    fun isCodeBreakerDs(code: String): Boolean {
        val first = tidyCode(code).substringBefore('\n').split('+').first().trim()
        val id = CBDS_FIRST.matchEntire(first)?.groupValues?.get(1) ?: return false
        return id.chunked(2).all { it.toInt(16).toChar().let { c -> c in 'A'..'Z' || c in '0'..'9' } }
    }

    /**
     * The code as it should be kept for [console]. N64 (plan-n64 Step 0): the core splits a GameShark code at any
     * non-hex character, so `8XXXXXXXYYYY` with no space would be one number that silently does nothing. Each such line
     * (or "+"-joined part) gets its space put in; anything else is left for [problem] to judge.
     * GameCube / Wii / PS2 (Windows only): `XXXXXXXXYYYYYYYY` gets its space too. PS2: pnach lines
     * (`patch=1,EE,ADDR,TYPE,VALUE`) become the two blocks LRPS2 / PCEE2 turn back into exactly that line (the "="
     * can't be kept: the server's code rule has no "="), and a pasted .pnach's own lines (gametitle=, comment=,
     * author=, description=, [sections], // notes) are left out.
     */
    fun normalise(console: Console?, code: String): String = when (console) {
        Console.N64 -> spaceParts(code) { N64_RUN.matches(it) }
        // GBA: mGBA's libretro reader takes each line as "XXXXXXXX YYYYYYYY" (or CodeBreaker "XXXXXXXX YYYY"); without the
        // space it reads 17 characters at a time, eating the next line's first, so the whole cheat silently does nothing.
        Console.GBA -> spaceParts(code) { HEX16.matches(it) || N64_RUN.matches(it) }
        // Review S10: a PS2 cheat that can't be converted safely is left as typed, so [problem] can say why.
        Console.PS2 -> if (pnachProblem(code) != null) tidyCode(code) else normaliseWindows(console, code)
        Console.GC, Console.WII -> normaliseWindows(console, code)
        else -> code
    }

    /** Each line's "+"-joined parts, trimmed; a part [needsSpace] says is one run gets its space after 8 characters. */
    private fun spaceParts(code: String, needsSpace: (String) -> Boolean): String =
        tidyCode(code).split('\n').joinToString("\n") { line ->
            line.split('+').joinToString("+") { p -> p.trim().let { if (needsSpace(it)) spaced(it) else it } }
        }

    private fun spaced(run: String) = run.substring(0, 8) + " " + run.substring(8)

    /** Every line and "+"-joined part of [body], trimmed, blanks left out. */
    private fun parts(body: String) = body.split('\n').flatMap { it.split('+') }.map { it.trim() }.filter { it.isNotEmpty() }

    private fun normaliseWindows(console: Console, code: String): String {
        val (prefix, body) = splitPrefix(console, tidyCode(code))
        val lines = parts(body)
            .filterNot { console == Console.PS2 && PNACH_META.containsMatchIn(it) }
            .map { l ->
                val p = if (console == Console.PS2) pnachToRaw(l) ?: l else l
                if (HEX16.matches(p)) spaced(p) else p
            }
        return prefix + lines.joinToString("\n")
    }

    /**
     * Review S10: PS2 pastes that can't become one cheat. A .pnach with more than one [section] holds several cheats.
     * And a byte / short / word line becomes an extended line (types 0 / 1 / 2), which LRPS2 runs in turn with the
     * other extended lines: after a two-line code (3040, 3050, 4, 5, 6) or a condition (D, E) it would be read as that
     * code's next line (pcsx2/Patch.cpp `PrevCheatType` / `SkipCount`), which a real byte / short / word line never is.
     */
    private fun pnachProblem(code: String): String? {
        val lines = tidyCode(code).split('\n').map { it.trim() }.filter { it.isNotEmpty() }
        if (lines.count { it.startsWith("[") } > 1) return PNACH_SECTIONS
        val pnach = lines.mapNotNull { PNACH.matchEntire(it)?.destructured }
        val converted = pnach.any { (place, cpu, _, type, _) -> place == "1" && cpu.equals("EE", true) && !type.equals("extended", true) }
        if (!converted) return null
        val extendedAddrs = pnach.filter { it.component4().equals("extended", true) }.map { it.component3().toLong(16) } +
            lines.flatMap { it.split('+') }.map { it.trim().replace(" ", "") }.filter { HEX16.matches(it) }.map { it.substring(0, 8).toLong(16) }
        return if (extendedAddrs.any { a -> (a ushr 28) in MULTI_LINE_TYPES || (a and 0xFFFF0000L) in TWO_LINE_INC_DEC }) PNACH_MIX else null
    }

    private val MULTI_LINE_TYPES = setOf(0x4L, 0x5L, 0x6L, 0xDL, 0xEL)
    private val TWO_LINE_INC_DEC = setOf(0x30400000L, 0x30500000L)
    const val PNACH_SECTIONS = "That's a .pnach with more than one cheat in it ([sections]). Add each cheat separately."
    const val PNACH_MIX = "A pnach byte, short or word line can't share a cheat with a two-line or conditional extended code " +
        "(3040, 3050, 4, 5, 6, D or E): the emulator would read it as part of that code. Add it as its own cheat."

    private val HEX16 = Regex("^[0-9A-Fa-f]{16}$")
    private val HEX8x2 = Regex("^[0-9A-Fa-f]{8} [0-9A-Fa-f]{8}$")
    /** Dolphin's encrypted Action Replay line (ActionReplay::DeserializeLine): XXXX-XXXX-XXXXX. */
    private val AR_ENCRYPTED = Regex("^[0-9A-Za-z]{4}-[0-9A-Za-z]{4}-[0-9A-Za-z]{5}$")
    private val PNACH_META = Regex("^(gametitle|comment|author|description)\\s*=|^\\[|^//", RegexOption.IGNORE_CASE)
    private val PNACH = Regex("^patch\\s*=\\s*([01])\\s*,\\s*(EE|IOP)\\s*,\\s*([0-9A-Fa-f]{1,8})\\s*,\\s*(extended|byte|short|word)\\s*,\\s*([0-9A-Fa-f]{1,8})$",
        RegexOption.IGNORE_CASE)
    /** The PS2's encrypted code families: CodeBreaker 7's BEEFC0DE key code and GameShark 3/4's DEADFACE seed. */
    private val PS2_KEY = Regex("^(BEEFC0D[EF]|DEADFACE)", RegexOption.IGNORE_CASE)

    /** GameCube: Dolphin's patch reads "AR:" / "GECKO:" in front of the whole code (tools/dolphin-patches/0001). Kept as typed. */
    private fun splitPrefix(console: Console, code: String): Pair<String, String> {
        if (console != Console.GC && console != Console.WII) return "" to code
        val m = Regex("^(AR|GECKO):\\s*", RegexOption.IGNORE_CASE).find(code) ?: return "" to code
        return m.value.trim().uppercase() + " " to code.substring(m.range.last + 1)
    }

    /**
     * A pnach line as the two blocks the PS2 patch (tools/lrps2-patches/0003) turns back into
     * `patch=1,EE,<a>,extended,<b>`: extended as written; byte / short / word as PCSX2's extended types 0 / 1 / 2.
     * Null when it can't be the same cheat that way (patch=0 runs once, IOP is another processor, a value too big).
     */
    private fun pnachToRaw(line: String): String? {
        val m = PNACH.matchEntire(line) ?: return null
        val (place, cpu, addrHex, type, valueHex) = m.destructured
        if (place != "1" || !cpu.equals("EE", ignoreCase = true)) return null
        val addr = addrHex.toLong(16)
        val value = valueHex.toLong(16)
        fun hex(v: Long) = java.lang.Long.toHexString(v).uppercase().padStart(8, '0')
        return when (type.lowercase()) {
            "extended" -> hex(addr) + " " + hex(value)
            else -> {
                if (addr > 0x0FFFFFFF) return null
                val (kind, max) = when (type.lowercase()) { "byte" -> 0L to 0xFFL; "short" -> 1L to 0xFFFFL; else -> 2L to 0xFFFFFFFFL }
                if (value > max) return null
                hex((kind shl 28) or addr) + " " + hex(value)
            }
        }
    }

    /**
     * Windows-only consoles (D218): what Shoalcade's Dolphin / LRPS2 / PCEE2 patches can read, judged on the
     * [normalise]d code. Null = fine, or a plain reason.
     */
    private fun windowsProblem(console: Console, code: String): String? {
        val (prefix, body) = splitPrefix(console, code)
        val lines = parts(body)
        if (lines.isEmpty()) return "Type the cheat code."
        if (console == Console.PS2) pnachProblem(code)?.let { return it }
        return when (console) {
            Console.GC -> {
                val ar = prefix.startsWith("AR")
                val dashed = lines.all { AR_ENCRYPTED.matches(it) }
                when {
                    ar && (dashed || lines.all { HEX8x2.matches(it) }) -> null
                    !ar && !prefix.startsWith("GECKO") && dashed -> null
                    !ar && lines.all { HEX8x2.matches(it) } -> null
                    lines.any { AR_ENCRYPTED.matches(it) } && lines.any { !AR_ENCRYPTED.matches(it) } ->
                        "An encrypted Action Replay code (XXXX-XXXX-XXXXX) can't be mixed with other lines. Add each code as its own cheat."
                    else -> GC_FORMAT
                }
            }
            Console.WII -> when {
                prefix.startsWith("AR") || lines.any { '-' in it } ->
                    "Wii cheats are Gecko (Ocarina) codes, like 04xxxxxx yyyyyyyy. Action Replay codes don't work on the Wii."
                lines.all { HEX8x2.matches(it) } -> null
                else -> "Wii codes look like 04xxxxxx yyyyyyyy: two blocks of 8 per line, one code per line."
            }
            else -> when { // PS2
                lines.any { '-' in it } -> // raw codes never have dashes; AR MAX codes are XXXXX-XXXXX-XXXXX
                    "That looks like an Action Replay MAX code. Encrypted PS2 codes don't work: look for the unencrypted (raw) version, like 2xxxxxxx yyyyyyyy."
                lines.any { PS2_KEY.containsMatchIn(it) } ->
                    "That's an encrypted CodeBreaker or GameShark code. Encrypted PS2 codes don't work: look for the unencrypted (raw) version, like 2xxxxxxx yyyyyyyy."
                lines.any { it.startsWith("patch", ignoreCase = true) } ->
                    "Only pnach lines like patch=1,EE,2xxxxxxx,extended,yyyyyyyy can be used (EE, and extended, byte, short or word)."
                lines.all { HEX8x2.matches(it) } -> null
                else -> "PS2 codes look like 2xxxxxxx yyyyyyyy: two blocks of 8 per line, one code per line (or pnach lines: patch=1,EE,…)."
            }
        }
    }

    private const val GC_FORMAT = "GameCube codes look like 04xxxxxx yyyyyyyy (Gecko) or XXXX-XXXX-XXXXX (encrypted Action Replay). " +
        "Put AR: in front of unencrypted Action Replay codes."

    /** Plain-English reason the name or code can't be used, or null if both are fine. [console]'s own code format is checked too (N64). */
    fun problem(name: String, code: String, console: Console? = null): String? {
        val n = name.split(' ', '\t').filter { it.isNotEmpty() }.joinToString(" ")
        val c = tidyCode(normalise(console, code))
        val bad = c.firstOrNull { !allowed(it) }
        val n64Bad = console == Console.N64 && c.isNotEmpty() &&
            c.split('\n').flatMap { it.split('+') }.any { !N64_CODE.matches(it.trim()) }
        val windows = if (console == Console.GC || console == Console.WII || console == Console.PS2) windowsProblem(console, c) else null
        return when {
            n.isEmpty() -> "Give the cheat a name."
            n.length > MAX_NAME -> "Names can be up to $MAX_NAME characters."
            n.any(Char::isISOControl) -> "The name has a character that can't be used."
            c.isEmpty() -> "Type the cheat code."
            windows != null -> windows // before the character rule: a pnach line's "=" gets its own words
            bad != null -> "Codes use only letters, numbers, spaces and + - : _ . , so \"$bad\" can't be used."
            c.length > MAX_CODE -> "That code is too long (up to $MAX_CODE characters)."
            c.count { it == '\n' } + 1 > MAX_LINES -> "That code has too many lines (up to $MAX_LINES)."
            n64Bad -> "N64 codes look like 8XXXXXXX YYYY: 8 characters, a space, then 4. One code per line."
            console == Console.NDS && isCodeBreakerDs(c) ->
                "CodeBreaker DS codes aren't supported (the DS engine only runs Action Replay codes). Look for the Action Replay version of this cheat."
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
        Console.NDS -> "DS: Action Replay codes (sites often call them GameShark DS: the same codes), two blocks of 8 per line, like 02xxxxxx yyyyyyyy. CodeBreaker DS codes don't work."
        Console.PSX -> "PlayStation: GameShark codes, like 8xxxxxxx yyyy. One code per line."
        Console.PSP -> "PSP: CWCheat codes, one per line, like _L 0x2xxxxxxx 0xyyyyyyyy."
        Console.N64 -> "Nintendo 64: GameShark codes, like 8XXXXXXX YYYY. One code per line."
        Console.N3DS -> "3DS: cheats aren't supported yet."
        // Windows only (D218): Shoalcade's Dolphin / LRPS2 / PCEE2 cheat patches (tools/*-patches), D-4 for GameCube.
        Console.GC -> "GameCube: Gecko codes, two blocks of 8 per line, like 04xxxxxx yyyyyyyy. Action Replay codes: " +
            "encrypted ones (XXXX-XXXX-XXXXX) as written, or put AR: in front of unencrypted ones."
        Console.WII -> "Wii: Gecko (Ocarina) codes, two blocks of 8 per line, like 04xxxxxx yyyyyyyy."
        Console.PS2 -> "PlayStation 2: unencrypted codes, two blocks of 8 per line, like 2xxxxxxx yyyyyyyy, or pnach lines " +
            "(patch=1,EE,…). Encrypted CodeBreaker, GameShark and Action Replay MAX codes don't work."
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

    /** [console]: codes saved before a console's spacing rule existed get it here too, so they work without re-typing. */
    fun ops(active: List<Cheat>, console: Console? = null): List<Op> =
        listOf(Op.Reset) + active.mapIndexed { i, c -> Op.Set(i, CheatRules.forCore(CheatRules.normalise(console, c.code))) }
}
