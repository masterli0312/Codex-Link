package com.codex.quota

import com.codex.quota.auth.AccountReauthenticationRules
import com.codex.quota.auth.DecodedTokenInfo
import org.junit.Assert.*
import org.junit.Test

class AccountReauthenticationRulesTest {
    private fun info(email: String? = "same@test.org", user: String? = "user", account: String? = "workspace") = DecodedTokenInfo(email, user, null, account)
    @Test fun changedWorkspaceCannotBeHiddenByMatchingEmail() {
        assertFalse(AccountReauthenticationRules.sameAccount(info(), "same@test.org", info(account = "different")))
        assertTrue(AccountReauthenticationRules.sameAccount(info(), "old@test.org", info(email = "new@test.org")))
    }
    @Test fun oldAccountsCanMatchByEmailButMissingOrDifferentIdentityIsRejected() {
        assertTrue(AccountReauthenticationRules.sameAccount(null, "Same@Test.org", info(account = null)))
        assertFalse(AccountReauthenticationRules.sameAccount(null, "original@test.org", info(account = null)))
        assertFalse(AccountReauthenticationRules.sameAccount(null, null, info()))
        assertFalse(AccountReauthenticationRules.sameAccount(info(account = null), "same@test.org", info(user = "other", account = null)))
    }
}
