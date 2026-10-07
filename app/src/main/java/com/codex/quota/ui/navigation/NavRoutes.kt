package com.codex.quota.ui.navigation

sealed class Screen(val route: String) {
    data object Dashboard : Screen("dashboard")
    data object Accounts : Screen("accounts")
    data object Settings : Screen("settings")
    data object AddAccount : Screen("add_account?reauthAccountId={reauthAccountId}") {
        fun createRoute(accountId: String? = null): String = if (accountId.isNullOrBlank()) "add_account"
            else "add_account?reauthAccountId=${android.net.Uri.encode(accountId)}"
    }
    data object Onboarding : Screen("onboarding")
    data object About : Screen("about")
    data object TaskHistory : Screen("task_history")
    data object TaskRecord : Screen("task_record/{recordId}") {
        fun createRoute(recordId: String): String = "task_record/$recordId"
    }
    data object SettingsPage : Screen("settings_page/{page}") {
        fun createRoute(page: String): String = "settings_page/$page"
    }
    data object CreditHistory : Screen("credit_history/{accountId}") {
        fun createRoute(accountId: String): String = "credit_history/${android.net.Uri.encode(accountId)}"
    }
    data object AccountDetail : Screen("account_detail/{accountId}") {
        fun createRoute(accountId: String): String = "account_detail/$accountId"
    }
}
