package com.codex.quota.ui.navigation
import androidx.compose.ui.res.stringResource
import com.codex.quota.R

import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.Dashboard
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.automirrored.outlined.Chat
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.navArgument
import androidx.navigation.navDeepLink
import com.codex.quota.CodexQuotaApplication
import com.codex.quota.domain.usecase.AddAccountUseCase
import com.codex.quota.domain.usecase.ObserveAccountsUseCase
import com.codex.quota.domain.usecase.RefreshAccountUseCase
import com.codex.quota.domain.usecase.RefreshAllAccountsUseCase
import com.codex.quota.domain.usecase.RemoveAccountUseCase
import com.codex.quota.domain.usecase.UpdateAccountUseCase
import com.codex.quota.ui.feature.about.AboutScreen
import com.codex.quota.ui.feature.accountdetail.AccountDetailScreen
import com.codex.quota.ui.feature.accountdetail.AccountDetailViewModel
import com.codex.quota.ui.feature.addaccount.AddAccountScreen
import com.codex.quota.ui.feature.addaccount.AddAccountViewModel
import com.codex.quota.ui.feature.dashboard.DashboardScreen
import com.codex.quota.ui.feature.dashboard.DashboardViewModel
import com.codex.quota.ui.feature.onboarding.OnboardingScreen
import com.codex.quota.ui.feature.settings.SettingsScreen
import com.codex.quota.ui.feature.settings.SettingsViewModel
import com.codex.quota.ui.util.scopedViewModel

@Composable
fun AppNavigation(
    app: CodexQuotaApplication,
    navController: NavHostController,
    startDestination: String,
    modifier: Modifier = Modifier
) {
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route

    val showBottomBar = currentRoute in listOf(Screen.Dashboard.route, Screen.Settings.route)

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = {
            if (showBottomBar) {
                NavigationBar(
                    containerColor = MaterialTheme.colorScheme.surface,
                    tonalElevation = 0.dp
                ) {
                    NavigationBarItem(
                        selected = currentRoute == Screen.Dashboard.route,
                        onClick = {
                            if (currentRoute != Screen.Dashboard.route) {
                                navController.navigate(Screen.Dashboard.route) {
                                    popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            }
                        },
                        icon = { Icon(if (currentRoute == Screen.Dashboard.route) Icons.Default.Dashboard else Icons.Outlined.Dashboard, contentDescription = null) },
                        label = { Text(stringResource(R.string.dashboard_title)) }
                    )
                    NavigationBarItem(
                        selected = currentRoute == Screen.TaskHistory.route,
                        onClick = {
                            if (currentRoute != Screen.TaskHistory.route) {
                                navController.navigate(Screen.TaskHistory.route) {
                                    popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            }
                        },
                        icon = { Icon(com.codex.quota.ui.components.ConversationNavIcon, contentDescription = null) },
                        label = { Text(stringResource(R.string.conversations_title)) }
                    )
                    NavigationBarItem(
                        selected = currentRoute == Screen.Settings.route,
                        onClick = {
                            if (currentRoute != Screen.Settings.route) {
                                navController.navigate(Screen.Settings.route) {
                                    popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            }
                        },
                        icon = { Icon(if (currentRoute == Screen.Settings.route) Icons.Default.Settings else Icons.Outlined.Settings, contentDescription = null) },
                        label = { Text(stringResource(R.string.settings_title)) }
                    )
                }
            }
        }
    ) { padding ->
        NavHost(
            navController = navController,
            startDestination = startDestination,
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .consumeWindowInsets(padding),
            enterTransition = { fadeIn(animationSpec = tween(150)) },
            exitTransition = { fadeOut(animationSpec = tween(150)) },
            popEnterTransition = { fadeIn(animationSpec = tween(150)) },
            popExitTransition = { fadeOut(animationSpec = tween(150)) }
        ) {
            composable(Screen.Onboarding.route) {
                OnboardingScreen(
                    onComplete = {
                        app.markOnboardingComplete()
                        navController.navigate(Screen.Dashboard.route) {
                            popUpTo(Screen.Onboarding.route) { inclusive = true }
                        }
                    }
                )
            }

            composable(
                route = Screen.Dashboard.route,
                deepLinks = listOf(
                    navDeepLink { uriPattern = "codexquota://dashboard" }
                )
            ) {
                val dashboardViewModel: DashboardViewModel = scopedViewModel {
                    DashboardViewModel(
                        observeAccountsUseCase = ObserveAccountsUseCase(app.repository),
                        repository = app.repository,
                        refreshAllAccountsUseCase = RefreshAllAccountsUseCase(app.repository)
                    )
                }
                DashboardScreen(
                    viewModel = dashboardViewModel,
                    onNavigateToAccountDetail = { accountId ->
                        navController.navigate(Screen.AccountDetail.createRoute(accountId))
                    },
                    onNavigateToCreditHistory = { navController.navigate(Screen.CreditHistory.createRoute(it)) },
                    onReauthenticate = { navController.navigate(Screen.AddAccount.createRoute(it)) { launchSingleTop = true } },
                    onNavigateToAddAccount = {
                        navController.navigate(Screen.AddAccount.createRoute())
                    }
                )
            }

            composable(
                route = Screen.AccountDetail.route,
                arguments = listOf(navArgument("accountId") { type = NavType.StringType }),
                deepLinks = listOf(
                    navDeepLink { uriPattern = "codexquota://account/{accountId}" }
                )
            ) { backStackEntry ->
                val accountId = backStackEntry.arguments?.getString("accountId").orEmpty()
                val detailViewModel: AccountDetailViewModel = scopedViewModel(key = accountId) {
                    AccountDetailViewModel(
                        context = app,
                        accountId = accountId,
                        repository = app.repository,
                        preferencesRepository = app.preferencesRepository,
                        consumeResetCredit = app.consumeResetCredit,
                        creditHistoryDao = app.database.creditHistoryDao(),
                        refreshAccountUseCase = RefreshAccountUseCase(app.repository),
                        updateAccountUseCase = UpdateAccountUseCase(app.repository),
                        removeAccountUseCase = RemoveAccountUseCase(app.repository)
                    )
                }
                AccountDetailScreen(
                    viewModel = detailViewModel,
                    onNavigateBack = { if (!navController.popBackStack()) navController.navigate(Screen.Dashboard.route) },
                    onCreditHistory = { navController.navigate(Screen.CreditHistory.createRoute(accountId)) },
                    onReauthenticate = { navController.navigate(Screen.AddAccount.createRoute(accountId)) { launchSingleTop = true } }
                )
            }

            composable(Screen.CreditHistory.route, arguments = listOf(navArgument("accountId") { type = NavType.StringType })) { entry ->
                com.codex.quota.ui.feature.credithistory.CreditHistoryScreen(app, entry.arguments?.getString("accountId").orEmpty()) {
                    if (!navController.popBackStack()) navController.navigate(Screen.Dashboard.route)
                }
            }

            composable(
                route = Screen.AddAccount.route,
                arguments = listOf(navArgument("reauthAccountId") { type = NavType.StringType; nullable = true; defaultValue = null }),
                deepLinks = listOf(
                    navDeepLink { uriPattern = "codexquota://oauth/callback?code={code}" },
                    navDeepLink { uriPattern = "codexquota://oauth/callback" }
                )
            ) { entry ->
                val reauthAccountId = entry.arguments?.getString("reauthAccountId")
                val addAccountViewModel: AddAccountViewModel = scopedViewModel(key = reauthAccountId ?: "new") {
                    AddAccountViewModel(
                        context = app,
                        addAccountUseCase = AddAccountUseCase(app.repository),
                        repository = app.repository,
                        reauthAccountId = reauthAccountId
                    )
                }

                LaunchedEffect(app.currentOAuthCallbackUri) {
                    val oauthUri = app.currentOAuthCallbackUri
                    if (oauthUri != null) {
                        app.currentOAuthCallbackUri = null
                        addAccountViewModel.handleOAuthCallbackUri(oauthUri)
                    }
                }

                AddAccountScreen(
                    viewModel = addAccountViewModel,
                    onNavigateBack = { navController.popBackStack() }
                )
            }

            composable(Screen.Settings.route) {
                val settingsViewModel: SettingsViewModel = scopedViewModel {
                    SettingsViewModel(
                        preferencesRepository = app.preferencesRepository,
                        accountRepository = app.repository
                    )
                }
                SettingsScreen(
                    viewModel = settingsViewModel,
                    onNavigate = { navController.navigate(Screen.SettingsPage.createRoute(it)) }
                )
            }

            composable(Screen.SettingsPage.route, arguments = listOf(navArgument("page") { type = NavType.StringType })) { backStackEntry ->
                val settingsViewModel: SettingsViewModel = scopedViewModel {
                    SettingsViewModel(app.preferencesRepository, app.repository)
                }
                SettingsScreen(settingsViewModel, page = backStackEntry.arguments?.getString("page").orEmpty(),
                    onNavigate = { navController.navigate(Screen.SettingsPage.createRoute(it)) },
                    onNavigateBack = { navController.popBackStack() })
            }

            composable(Screen.About.route) {
                AboutScreen(onNavigateBack = { navController.popBackStack() })
            }
            composable(Screen.TaskHistory.route,
                enterTransition = { EnterTransition.None }, exitTransition = { ExitTransition.None },
                popEnterTransition = { EnterTransition.None }, popExitTransition = { ExitTransition.None }) { entry ->
                com.codex.quota.ui.feature.taskhistory.ConversationListScreen(
                    onBack = { navController.navigate(Screen.Dashboard.route) {
                        launchSingleTop = true
                        popUpTo(Screen.Dashboard.route) { saveState = true }
                    } },
                    onSettings = { navController.navigate(Screen.SettingsPage.createRoute("task_notifications")) },
                    onOpen = { recordId ->
                        if (navController.currentBackStackEntry == entry) {
                            navController.navigate(Screen.TaskRecord.createRoute(recordId)) { launchSingleTop = true }
                        }
                    })
            }
            composable(Screen.TaskRecord.route, arguments = listOf(navArgument("recordId") { type = NavType.StringType }),
                enterTransition = { EnterTransition.None }, exitTransition = { ExitTransition.None },
                popEnterTransition = { EnterTransition.None }, popExitTransition = { ExitTransition.None }) { entry ->
                com.codex.quota.ui.feature.taskhistory.ConversationScreen(entry.arguments?.getString("recordId").orEmpty(),
                    onOpen = { recordId ->
                        if (navController.currentBackStackEntry == entry && entry.arguments?.getString("recordId") != recordId)
                            navController.navigate(Screen.TaskRecord.createRoute(recordId))
                    }) {
                    if (!navController.popBackStack()) navController.navigate(Screen.TaskHistory.route)
                }
            }
        }
    }
}
