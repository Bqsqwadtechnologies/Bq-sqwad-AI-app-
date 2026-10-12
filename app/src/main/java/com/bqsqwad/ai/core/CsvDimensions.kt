package com.bqsqwad.ai.core

internal data class CsvDimensions(val dataRows: Int, val columns: Int)

/** Counts CSV records and columns without splitting commas inside quoted fields. */
internal fun detectCsvDimensions(text: String): CsvDimensions {
    if (text.isBlank()) return CsvDimensions(0, 0)
    var records = 0
    var columns = 1
    var maxColumns = 1
    var inQuotes = false
    var rowHasContent = false
    var index = 0

    fun finishRecord() {
        if (rowHasContent || columns > 1) {
            records += 1
            maxColumns = maxOf(maxColumns, columns)
        }
        columns = 1
        rowHasContent = false
    }

    while (index < text.length) {
        val ch = text[index]
        when {
            ch == '"' -> {
                if (inQuotes && index + 1 < text.length && text[index + 1] == '"') {
                    index += 1
                } else {
                    inQuotes = !inQuotes
                }
                rowHasContent = true
            }
            ch == ',' && !inQuotes -> {
                columns += 1
                rowHasContent = true
            }
            ch == '\n' && !inQuotes -> finishRecord()
            ch == '\r' && !inQuotes -> {
                if (index + 1 >= text.length || text[index + 1] != '\n') finishRecord()
            }
            !ch.isWhitespace() -> rowHasContent = true
        }
        index += 1
    }
    if (rowHasContent || columns > 1) finishRecord()
    return CsvDimensions((records - 1).coerceAtLeast(0), if (records == 0) 0 else maxColumns)
}
