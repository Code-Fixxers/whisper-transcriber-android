package com.whispertranscriber.service

object RealtimeTextEdit {
    data class State(
        val start: Int,
        val text: String
    )

    data class Result(
        val fieldText: String,
        val selectionStart: Int,
        val selectionEnd: Int,
        val state: State
    )

    fun apply(
        fieldText: String,
        selectionStart: Int,
        selectionEnd: Int,
        state: State?,
        transcript: String
    ): Result? {
        if (state != null) {
            val start = state.start
            val end = start + state.text.length
            if (start < 0 || start > fieldText.length || end > fieldText.length) return null
            if (fieldText.substring(start, end) != state.text) return null

            val newText = fieldText.substring(0, start) + transcript + fieldText.substring(end)
            val cursor = start + transcript.length
            return Result(
                fieldText = newText,
                selectionStart = cursor,
                selectionEnd = cursor,
                state = State(start = start, text = transcript)
            )
        }

        val safeStart = selectionStart.takeIf { it in 0..fieldText.length } ?: fieldText.length
        val safeEnd = selectionEnd.takeIf { it in 0..fieldText.length } ?: safeStart
        val rangeStart = minOf(safeStart, safeEnd)
        val rangeEnd = maxOf(safeStart, safeEnd)
        val newText = fieldText.substring(0, rangeStart) + transcript + fieldText.substring(rangeEnd)
        val cursor = rangeStart + transcript.length

        return Result(
            fieldText = newText,
            selectionStart = cursor,
            selectionEnd = cursor,
            state = State(start = rangeStart, text = transcript)
        )
    }
}
