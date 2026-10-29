package app.agentime

/** In-memory only session. No persistence of Q&A text. */
class AgentSession {
    enum class Mode { ASK, ANSWER, REWRITE }

    data class Context(val label: String, val text: String, val source: Source) {
        enum class Source { SELECTION, DRAFT, PASTE }
    }

    /**
     * Host identity snapshot for a field, used to guard writes.
     *
     * generation: bumped in `onStartInputView` for every distinct editor session;
     *             late async results tied to a stale generation are dropped.
     * fieldId:    EditorInfo.fieldId when available; -1 when the host did not
     *             provide one. Treat as part of the identity.
     */
    data class Target(
        val generation: Long,
        val fieldId: Int,
        val packageName: String,
        val inputType: Int,
        val selectionStart: Int,
        val selectionEnd: Int,
        val draftAtCreation: String,
        val sensitive: Boolean
    )

    /**
     * Transaction snapshot captured immediately before a successful replace.
     * Undo requires the host to be at the same identity with the exact same
     * full text and exact same [from, to) substring.
     */
    data class UndoSnapshot(
        val generation: Long,
        val fieldId: Int,
        val packageName: String,
        val inputType: Int,
        val from: Int,
        val to: Int,
        val original: String,
        val inserted: String,
        val fullTextAfter: String
    )

    /** Retained Q/A turns of the current session (for followups in same context). */
    data class Turn(val question: String, val answer: String)

    var lastActivityAt: Long = 0L
    var mode: Mode = Mode.ASK
    var internalDraft: String = ""
    var followupDraft: String = ""
    var answer: String = ""

    /** Kind (ask/rewrite) that produced [answer], so ANSWER mode can restore the right UI. */
    var answerKind: String = "ask"

    var statusText: String = ""
    var contexts: MutableList<Context> = mutableListOf()
    var target: Target? = null
    var rewriteOriginal: String? = null
    var undoSnapshot: UndoSnapshot? = null
    var pendingRequestId: String? = null
    var turns: MutableList<Turn> = mutableListOf()

    fun touch(now: Long = System.currentTimeMillis()) { lastActivityAt = now }
    fun expired(now: Long = System.currentTimeMillis()): Boolean =
        lastActivityAt > 0 && now - lastActivityAt > IDLE_MS
    fun clear() {
        internalDraft = ""
        followupDraft = ""
        answer = ""
        answerKind = "ask"
        statusText = ""
        contexts.clear()
        target = null
        rewriteOriginal = null
        undoSnapshot = null
        pendingRequestId = null
        turns.clear()
        mode = Mode.ASK
    }

    companion object {
        const val IDLE_MS: Long = 15L * 60L * 1000L
    }
}
