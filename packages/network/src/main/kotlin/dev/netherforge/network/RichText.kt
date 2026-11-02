package dev.netherforge.network

/**
 * What a message the admin API shows a player may do: its text is MiniMessage (the proxy reads it with Adventure's),
 * and the hub holds it to what a service outside the network may put in front of a player before it goes anywhere.
 *
 * - A click may run or suggest a command (as if the player typed it: the proxy and the server handle it with the
 *   player's own permissions), copy text, or open a link: `run_command`, `suggest_command`, `copy_to_clipboard`,
 *   `open_url`. Nothing else (`open_file`, `change_page`, `show_dialog`, `custom`, and whatever the game adds).
 * - A hover may only show text: `show_text`, not an item or an entity.
 * - The text (and a title's subtitle) is at most the network's value limit, as anything else the hub relays.
 *
 * The tags are found wherever they are, a hover's text included (a click inside it is still a click), by name without
 * case as MiniMessage reads them. A tag written escaped (`\<click:...>`) is text, not a tag. The proxy checks what it
 * built again before it shows it ([ALLOWED_CLICKS]), so a reading of the text that differs from Adventure's can only
 * refuse more.
 */
object RichText {
    /** The click actions a message may carry, as MiniMessage and the game name them. */
    val ALLOWED_CLICKS = setOf("run_command", "suggest_command", "copy_to_clipboard", "open_url")

    /** The hover actions a message may carry. */
    val ALLOWED_HOVERS = setOf("show_text")

    private val TAG = Regex("""(?<!\\)<\s*(click|hover)\s*:\s*['"]?([A-Za-z_]*)""", RegexOption.IGNORE_CASE)

    /** Why [text] and [subtitle] can't be shown to a player as the admin API's message, or null when they can. */
    fun problem(text: String, subtitle: String?, maxBytes: Int): String? {
        val size = text.encodeToByteArray().size + (subtitle?.encodeToByteArray()?.size ?: 0)
        if (size > maxBytes) return "the message is $size bytes, over the network's limit of $maxBytes"
        for (part in listOfNotNull(text, subtitle)) {
            for (match in TAG.findAll(part)) {
                val tag = match.groupValues[1].lowercase()
                val action = match.groupValues[2].lowercase()
                val allowed = if (tag == "click") ALLOWED_CLICKS else ALLOWED_HOVERS
                if (action !in allowed) {
                    return "<$tag:${action.ifEmpty { "…" }}> isn't allowed in a message: a $tag may only " +
                        allowed.sorted().joinToString()
                }
            }
        }
        return null
    }
}
