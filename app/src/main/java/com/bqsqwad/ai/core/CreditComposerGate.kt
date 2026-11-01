package com.bqsqwad.ai.core

/**
 * Composer typing must never wait on a live credit refresh.
 * Server still enforces real balance on send.
 */
object CreditComposerGate {
    const val BLOCK_TYPING_WHILE_LOADING = false
    const val ALLOW_TYPE_WHEN_EXHAUSTED = true
}
