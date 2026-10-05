package com.bqsqwad.ai.core

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import java.io.IOException

suspend fun <T> withRetry(
    attempts: Int = 3,
    initialDelayMs: Long = 600,
    factor: Double = 2.0,
    shouldRetry: (Throwable) -> Boolean = { it is IOException },
    block: suspend () -> T,
): T {
    require(attempts >= 1) { "attempts must be at least 1" }
    var wait = initialDelayMs
    repeat(attempts - 1) {
        try { return block() } catch (t: Throwable) {
            if (t is CancellationException || !shouldRetry(t)) throw t
        }
        delay(wait)
        wait = (wait * factor).toLong()
    }
    return block()
}
