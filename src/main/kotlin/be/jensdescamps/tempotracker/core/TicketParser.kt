package be.jensdescamps.tempotracker.core

import java.util.concurrent.ConcurrentHashMap

/** Extracts a Jira issue key from a branch name. */
object TicketParser {
    /** Project key (letters, digits, underscore — starting with a letter) followed by `-` and a number. */
    const val DEFAULT_PATTERN = "([A-Z][A-Z0-9_]+-\\d+)"

    private val cache = ConcurrentHashMap<String, Regex>()

    /**
     * @param pattern regex; if it has a capturing group, group 1 is the key, otherwise the whole match.
     * @param allowedProjectKeys when non-empty, only keys of these Jira projects are accepted.
     */
    fun parse(
        branch: String?,
        pattern: String = DEFAULT_PATTERN,
        allowedProjectKeys: Collection<String> = emptyList(),
    ): String? {
        if (branch.isNullOrBlank()) return null
        val regex = compile(pattern) ?: return null
        val allowed = allowedProjectKeys.map { it.trim().uppercase() }.filter { it.isNotEmpty() }.toSet()
        return regex.findAll(branch)
            .map { m -> (m.groups[1]?.value ?: m.value).uppercase() }
            .firstOrNull { allowed.isEmpty() || it.substringBeforeLast('-') in allowed }
    }

    fun isValidPattern(pattern: String): Boolean = compile(pattern) != null

    private fun compile(pattern: String): Regex? =
        cache[pattern] ?: runCatching { Regex(pattern) }.getOrNull()?.also { cache[pattern] = it }
}
