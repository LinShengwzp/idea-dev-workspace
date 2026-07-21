package com.anmi.devworkspace.terminal

class TerminalOutputMarkerParser(
    executionId: String,
) {
    private val beginMarker = "__DEV_TASK_BEGIN__:$executionId"
    private val exitMarkerPrefix = "__DEV_TASK_EXIT__:$executionId:"
    private val beginPattern = Regex("${Regex.escape(beginMarker)}(?=\\r|\\n)")
    private val exitPattern = Regex("${Regex.escape(exitMarkerPrefix)}([0-9]+)(?=\\r|\\n)")
    private val retainedCharacterCount = maxOf(beginMarker.length, exitMarkerPrefix.length + MAX_EXIT_CODE_LENGTH) + 1
    private var tail = ""
    private var beganEmitted = false
    private var exitedEmitted = false

    fun accept(chunk: String): List<MarkerEvent> {
        if (chunk.isEmpty() || exitedEmitted) return emptyList()

        val candidate = tail + chunk
        var searchable = candidate
        val events = buildList {
            if (!beganEmitted) {
                val begin = beginPattern.find(candidate) ?: return@buildList
                beganEmitted = true
                add(MarkerEvent.Began)
                searchable = candidate.substring(begin.range.last + 1)
            }

            if (!exitedEmitted) {
                val exitCode = exitPattern.find(searchable)
                    ?.groupValues
                    ?.get(1)
                    ?.toIntOrNull()
                if (exitCode != null && exitCode >= 0) {
                    exitedEmitted = true
                    add(MarkerEvent.Exited(exitCode))
                }
            }
        }
        tail = searchable.takeLast(retainedCharacterCount)
        return events
    }

    private companion object {
        const val MAX_EXIT_CODE_LENGTH = 10
    }
}

sealed interface MarkerEvent {
    data object Began : MarkerEvent

    data class Exited(val code: Int) : MarkerEvent
}
