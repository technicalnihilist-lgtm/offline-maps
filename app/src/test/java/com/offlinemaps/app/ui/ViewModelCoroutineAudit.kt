package com.offlinemaps.app.ui

/**
 * A text audit of Kotlin sources for coroutines that are not owned by the view model that started
 * them (OPTIMIZATION.md H-04).
 *
 * `viewModelScope` is cancelled in `ViewModel.onCleared`, so work launched in it stops when the
 * screen it was started for goes away. Work launched anywhere else - `GlobalScope`, a
 * `CoroutineScope(...)` built inside the view model, or a scope handed in from outside - goes on
 * running after that: it keeps whatever the launch block captured alive and can write into a state
 * flow nobody is collecting any more.
 *
 * The rules are deliberately narrow, so that the audit has no false positives on the shapes this
 * codebase actually uses:
 *
 * * `GlobalScope` is reported wherever it appears, in any source file.
 * * In a file that declares a view model, a `CoroutineScope(...)` is reported, and so is a `launch`
 *   with an explicit receiver other than `viewModelScope`. A receiverless `launch` inherits the
 *   enclosing coroutine and so cannot escape anything, and is left alone.
 * * Process-lifetime work - the download engine, the navigation manager, the location source - lives
 *   outside view models on purpose, in scopes their owner cancels, and is left alone.
 */
internal object ViewModelCoroutineAudit {

    data class Finding(val file: String, val line: Int, val detail: String) {
        override fun toString(): String = "$file:$line: $detail"
    }

    /** A view model declaration: `class X(...) : ViewModel()`. */
    private val VIEW_MODEL_DECLARATION = Regex(""":\s*ViewModel\s*\(""")

    private val GLOBAL_SCOPE = Regex("""\bGlobalScope\b""")

    private val SCOPE_CONSTRUCTION = Regex("""\bCoroutineScope\s*\(""")

    /** `.launch {` / `.launch(` - the receiver in front of the dot decides who owns the job. */
    private val LAUNCH = Regex("""\.\s*launch\s*[({]""")

    fun isViewModelSource(text: String): Boolean = VIEW_MODEL_DECLARATION.containsMatchIn(text)

    fun audit(sources: Map<String, String>): List<Finding> {
        val findings = mutableListOf<Finding>()
        sources.forEach { (file, text) ->
            val lines = text.lines()
            val viewModel = isViewModelSource(text)
            lines.forEachIndexed { index, line ->
                val number = index + 1
                if (GLOBAL_SCOPE.containsMatchIn(line)) {
                    findings += Finding(file, number, "GlobalScope is not cancelled by onCleared")
                }
                if (!viewModel) return@forEachIndexed
                if (SCOPE_CONSTRUCTION.containsMatchIn(line)) {
                    findings += Finding(
                        file,
                        number,
                        "view model builds its own CoroutineScope, which onCleared cannot cancel",
                    )
                }
                LAUNCH.findAll(line).forEach { match ->
                    val receiver = receiverOf(lines, index, match.range.first)
                    if (receiver != null && receiver != "viewModelScope") {
                        findings += Finding(file, number, "launch on '$receiver' outlives the view model")
                    }
                }
            }
        }
        return findings
    }

    /**
     * The identifier the `.launch` is called on, or null when the call has no receiver of its own.
     *
     * A line that begins with the dot belongs to the line above it (`viewModelScope` on one line,
     * `.launch {` on the next), so the previous non-blank line is consulted for those.
     */
    private fun receiverOf(lines: List<String>, index: Int, dotIndex: Int): String? {
        val before = lines[index].substring(0, dotIndex).trimEnd()
        val text = if (before.isEmpty()) previousCodeLine(lines, index) else before
        val identifier = text.takeLastWhile { it.isLetterOrDigit() || it == '_' || it == '.' }.trim('.')
        return identifier.ifEmpty { null }
    }

    private fun previousCodeLine(lines: List<String>, index: Int): String {
        for (previous in index - 1 downTo 0) {
            val line = lines[previous].trimEnd()
            if (line.isNotEmpty()) return line
        }
        return ""
    }
}
