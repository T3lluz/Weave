package com.t3lluz.weave.data.link

/** Reads a streaming answer's thinking for the shortcut sheet's live line. */
object ThinkingLine {
    private val HEADING = Regex("""\*\*([^*\n]{3,80})\*\*""")

    /**
     * The newest heading in the model's thinking summary ("**Planning the layout**"), else its
     * last sentence, cut to a line. Null while there is nothing worth showing.
     */
    fun of(thinking: CharSequence): String? {
        val text = thinking.toString()
        HEADING.findAll(text).lastOrNull()?.let { return it.groupValues[1].trim() }
        val sentence = text.replace('\n', ' ').split(Regex("""(?<=[.!?])\s+""")).map { it.trim() }
            .lastOrNull { it.length >= 12 } ?: return null
        return if (sentence.length <= 64) sentence else sentence.take(63).trimEnd() + "…"
    }
}
