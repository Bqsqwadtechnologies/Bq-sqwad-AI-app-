package com.bqsqwad.ai.core

/** Only ordinary, enabled-history conversations may be automatically persisted. */
internal fun shouldAutoSaveChat(privateChat: Boolean, historyEnabled: Boolean, autoSaveEnabled: Boolean): Boolean =
    !privateChat && historyEnabled && autoSaveEnabled
