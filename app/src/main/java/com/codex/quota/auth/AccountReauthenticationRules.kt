package com.codex.quota.auth

/** Compare stable identities when available, otherwise the original account's email. */
internal object AccountReauthenticationRules {
    fun sameAccount(previous: DecodedTokenInfo?, email: String?, incoming: DecodedTokenInfo?): Boolean {
        if (incoming == null) return false
        val oldId = previous?.chatgptAccountId?.takeIf(String::isNotBlank)
        val newId = incoming.chatgptAccountId?.takeIf(String::isNotBlank)
        if (oldId != null && newId != null) return oldId == newId
        val oldUser = previous?.userId?.takeIf(String::isNotBlank)
        val newUser = incoming.userId?.takeIf(String::isNotBlank)
        if (oldUser != null && newUser != null) return oldUser == newUser
        val oldEmail = email?.takeIf(String::isNotBlank) ?: previous?.email?.takeIf(String::isNotBlank)
        return oldEmail != null && oldEmail.trim().equals(incoming.email?.trim(), ignoreCase = true)
    }
}
