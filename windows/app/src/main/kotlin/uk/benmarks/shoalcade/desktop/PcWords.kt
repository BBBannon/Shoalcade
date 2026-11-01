package uk.benmarks.shoalcade.desktop

/**
 * The shared phone files say "phone" where the Windows app means this PC ("⬇ On phone only" → "⬇ On this PC only",
 * "on this phone" → "on this PC"). Their words are the one list (docs/wording.md), so they're kept and only these
 * phrases are swapped, in this one tested place.
 */
object PcWords {
    private val swaps = listOf(
        // Saves (the phone's SaveSync / SavePlan / SyncAll / GameActivity words). "the phone app" (a save's source) stays.
        "Keep the phone's" to "Keep this PC's",
        "The phone's save" to "This PC's save",
        "the phone's save" to "this PC's save",
        "Use the phone save" to "Use this PC's save",
        "the phone's backups" to "this PC's backups",
        "Phone save: " to "PC save: ",
        "phone save" to "PC save",
        "the phone is back online" to "this PC is back online",
        "brought to the phone" to "brought to this PC",
        "⬇ On phone only" to "⬇ On this PC only",
        "On phone" to "On this PC",
        "on this phone" to "on this PC",
        "on the phone" to "on this PC",
        "from phone" to "from this PC",
        "from the phone" to "from this PC",
        "this phone" to "this PC",
    )

    fun of(text: String): String = swaps.fold(text) { t, (from, to) -> t.replace(from, to) }

    /**
     * Link play: the shared link code (Link.kt's handshake, LinkRelay.kt, Celio.kt) speaks of "the other phone" and
     * "the host phone". From a PC the other side may be a phone or another PC, so those become "the other player" and
     * "the host"; then the usual swaps ([of]) for "this phone".
     */
    fun link(text: String): String = of(linkSwaps.fold(text) { t, (from, to) -> t.replace(from, to) })

    private val linkSwaps = listOf(
        "Update the app on the other phone." to "Update Shoalcade on the other device.",
        "Update the app on this phone." to "Update Shoalcade on this PC.",
        "That isn't a Shoalcade phone." to "That isn't Shoalcade.",
        "This phone has an older version" to "This PC has an older version",
        "That phone doesn't know the PIN" to "That host doesn't know the PIN",
        "Check the phone's internet connection." to "Check this PC's internet connection.",
        "this phone's link data" to "this PC's link data",
        "Two Shoalcade phones can't meet over Celio Link" to "Two Shoalcade players can't meet over Celio Link",
        "On the other phone, tap" to "On their phone or PC, tap",
        "two phones" to "two players",
        "both phones" to "both players",
        "the host phone" to "the host",
        "The other phone" to "The other player",
        "the other phone" to "the other player",
    )
}
