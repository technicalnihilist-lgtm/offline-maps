package com.offlinemaps.core.offline

/**
 * The validator stored in the `<name>.meta` sidecar that sits next to `<name>.part`.
 *
 * A partial file is only useful if the bytes already on disk still belong to the representation the
 * server would serve now. The sidecar is what makes that check possible after a process restart: it
 * carries the `ETag` / `Last-Modified` the bytes were written under, so the resume request can send
 * `If-Range` and let the server decide, instead of splicing whatever it happens to return.
 *
 * The on-disk layout is the three positional lines this sidecar has always used
 * (`etag`, `lastModified`, `totalBytes`), so a sidecar written by an earlier build still parses.
 */
data class ResumeMeta(
    val etag: String? = null,
    val lastModified: String? = null,
    val totalBytes: Long = 0L,
) {
    /**
     * The `If-Range` value for a resume: the strong ETag when the server sent one, otherwise the
     * HTTP date from `Last-Modified`. `null` means nothing was recorded and a resume cannot be
     * validated - see [ResumePolicy].
     */
    val validator: String?
        get() = etag?.takeIf { it.isNotBlank() } ?: lastModified?.takeIf { it.isNotBlank() }

    companion object {
        val EMPTY = ResumeMeta()

        /** Parses a sidecar body; anything unreadable degrades to [EMPTY], never throws. */
        fun parse(text: String?): ResumeMeta {
            if (text.isNullOrBlank()) return EMPTY
            val lines = text.split('\n').map { it.trimEnd('\r') }
            return ResumeMeta(
                etag = lines.getOrNull(0)?.trim()?.takeIf { it.isNotEmpty() },
                lastModified = lines.getOrNull(1)?.trim()?.takeIf { it.isNotEmpty() },
                totalBytes = lines.getOrNull(2)?.trim()?.toLongOrNull() ?: 0L,
            )
        }

        /** Serialises in the same three-line layout; a value can never break the line structure. */
        fun format(meta: ResumeMeta): String =
            "${meta.etag.oneLine()}\n${meta.lastModified.oneLine()}\n${meta.totalBytes}"

        private fun String?.oneLine(): String = this.orEmpty().replace('\r', ' ').replace('\n', ' ')
    }
}

enum class ResumeDecision { RESUME, RESTART }

/**
 * Decides whether the bytes already in `<name>.part` may be kept.
 *
 * Pure, so the rule is testable without a server: a partial is kept only when it can be validated
 * against the recorded validator and is not obviously inconsistent. Anything else restarts from
 * zero, which costs bandwidth but can never install a package spliced from two upstream files.
 */
object ResumePolicy {
    fun decide(partBytes: Long, meta: ResumeMeta, expectedBytes: Long = 0L): ResumeDecision = when {
        // Nothing on disk to resume from.
        partBytes <= 0L -> ResumeDecision.RESTART
        // No validator was recorded, so a resume would be a guess.
        meta.validator == null -> ResumeDecision.RESTART
        // The partial claims to be longer than the file it is a prefix of.
        meta.totalBytes > 0L && partBytes > meta.totalBytes -> ResumeDecision.RESTART
        else -> ResumeDecision.RESUME
    }
}
