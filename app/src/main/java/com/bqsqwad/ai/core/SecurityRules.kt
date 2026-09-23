package com.bqsqwad.ai.core

object SecurityRules {
    const val ADMIN_EMAIL = "isaacoshiomole0@gmail.com"

    fun canOpenAdmin(account: Account?): Boolean =
        account?.authenticated == true &&
            account.email.equals(ADMIN_EMAIL, ignoreCase = true)

    fun secretMayEnterClientApp(): Boolean = false
}
