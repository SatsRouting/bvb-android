package com.bvb.android

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.core.view.WindowCompat
import androidx.compose.foundation.Image
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ListAlt
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.automirrored.filled.ShowChart
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CardGiftcard
import androidx.compose.material.icons.filled.Forum
import androidx.compose.material.icons.filled.Gavel
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SupportAgent
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.FloatingActionButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ScaffoldDefaults
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import kotlinx.coroutines.launch
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.bvb.android.core.notifications.LocalNotifier
import com.bvb.android.core.security.BiometricUnlock
import com.bvb.android.ui.components.AppSnackbar
import com.bvb.android.core.session.SessionManager
import com.bvb.android.core.sse.SseClient
import com.bvb.android.data.repository.AuthRepository
import com.bvb.android.feature.auth.AppLockScreen
import com.bvb.android.feature.auth.CreateAvatarScreen
import com.bvb.android.feature.auth.LoginScreen
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import com.bvb.android.feature.chat.ChatScreen
import com.bvb.android.feature.disputes.DisputeDetailScreen
import com.bvb.android.feature.disputes.DisputesScreen
import com.bvb.android.feature.learn.LearnScreen
import com.bvb.android.feature.messages.MessagesScreen
import com.bvb.android.feature.messages.SupportChatScreen
import com.bvb.android.feature.messages.SupportTicketSheet
import com.bvb.android.feature.order.MyOrdersScreen
import com.bvb.android.feature.recovery.RecoveryScreen
import com.bvb.android.feature.services.ServicesScreen
import com.bvb.android.feature.marketplace.MarketplaceScreen
import com.bvb.android.feature.notifications.NotificationsScreen
import com.bvb.android.feature.order.CreateOrderSheet
import com.bvb.android.feature.order.OrderDetailScreen
import com.bvb.android.feature.settings.SettingsScreen
import com.bvb.android.feature.trade.TradeDetailScreen
import com.bvb.android.feature.trade.TradesScreen
import com.bvb.android.ui.navigation.Routes
import com.bvb.android.ui.theme.BvbTheme
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

@HiltViewModel
class AppViewModel @Inject constructor(
    val session: SessionManager,
    val sse: SseClient,
    val biometric: BiometricUnlock,
    private val authRepository: AuthRepository,
    // Eagerly created so SSE notifications surface as local notifications.
    @Suppress("unused") private val localNotifier: LocalNotifier,
) : ViewModel() {
    /**
     * True after a cold start when biometric unlock is enrolled and the
     * in-memory password has not been restored yet. The JWT on disk is not
     * enough: the lock screen stays up until [unlockSession] succeeds.
     */
    private val _locked = MutableStateFlow(
        session.token != null && biometric.isEnabled && session.sessionPassword == null,
    )
    val locked: StateFlow<Boolean> = _locked

    fun logout() {
        viewModelScope.launch {
            // Clears the session even if the network call fails; the
            // isLoggedIn observer then navigates back to the login screen.
            authRepository.logout()
            _locked.value = false
        }
    }

    /**
     * Restores the in-memory session (PGP keys + password) after a cold start
     * and lifts the lock screen. [onResult] is invoked on the main thread.
     */
    fun unlockSession(password: String, onResult: (Boolean) -> Unit) {
        viewModelScope.launch {
            val ok = runCatching { authRepository.unlockPgpKeys(password) }.isSuccess
            if (ok) _locked.value = false
            onResult(ok)
        }
    }
}

@AndroidEntryPoint
class MainActivity : FragmentActivity() {
    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {}

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        setContent {
            // Status/navigation bar icons (clock, battery, network) must be dark
            // on the light theme and light on the dark theme, or they blend into
            // the transparent system bars and become unreadable.
            val darkTheme = isSystemInDarkTheme()
            SideEffect {
                val insets = WindowCompat.getInsetsController(window, window.decorView)
                insets.isAppearanceLightStatusBars = !darkTheme
                insets.isAppearanceLightNavigationBars = !darkTheme
            }
            BvbTheme {
                BvbApp()
            }
        }
    }
}

private data class DrawerPage(val route: String, val label: String, val icon: @Composable () -> Unit)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BvbApp(appViewModel: AppViewModel = hiltViewModel()) {
    val navController = rememberNavController()
    val isLoggedIn by appViewModel.session.isLoggedIn.collectAsState()
    val locked by appViewModel.locked.collectAsState()

    DisposableEffect(isLoggedIn, locked) {
        if (isLoggedIn && !locked) appViewModel.sse.connect()
        onDispose { appViewModel.sse.disconnect() }
    }

    // Full-screen lock: biometric enrolled + cold start. Do not render the
    // rest of the app until the user unlocks with biometrics or password.
    if (isLoggedIn && locked) {
        AppLockScreen(appViewModel)
        return
    }

    // When the session dies (expired/revoked token, manual logout) drop the
    // whole back stack and land on the login screen; startDestination alone
    // is only read once, so an explicit navigation is required.
    LaunchedEffect(isLoggedIn) {
        if (!isLoggedIn) {
            val current = navController.currentBackStackEntry?.destination?.route
            if (current != null && current != Routes.LOGIN && current != Routes.CREATE_AVATAR) {
                navController.navigate(Routes.LOGIN) {
                    popUpTo(0) { inclusive = true }
                }
            }
        }
    }

    // Mirrors the web app navbar (Alerts is reachable from the bell icon instead).
    val pages = listOf(
        DrawerPage(Routes.MARKETPLACE, "Marketplace") { Icon(Icons.AutoMirrored.Filled.ShowChart, null) },
        DrawerPage(Routes.TRADES, "Trades") { Icon(Icons.Default.SwapHoriz, null) },
        DrawerPage(Routes.MY_ORDERS, "My Orders") { Icon(Icons.AutoMirrored.Filled.ListAlt, null) },
        DrawerPage(Routes.MESSAGES, "Messages") { Icon(Icons.Default.Forum, null) },
        DrawerPage(Routes.SERVICES, "Services") { Icon(Icons.Default.CardGiftcard, null) },
        DrawerPage(Routes.DISPUTES, "Disputes") { Icon(Icons.Default.Gavel, null) },
        DrawerPage(Routes.RECOVERY, "Recovery") { Icon(Icons.Default.Restore, null) },
        DrawerPage(Routes.LEARN, "Learn") { Icon(Icons.AutoMirrored.Filled.MenuBook, null) },
        DrawerPage(Routes.SETTINGS, "Settings") { Icon(Icons.Default.Settings, null) },
    )

    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route
    // Top/bottom chrome only on the main pages; detail screens have their own back bar.
    val showBars = isLoggedIn &&
        (pages.any { it.route == currentRoute } || currentRoute == Routes.NOTIFICATIONS)

    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()

    // Global snackbar host: screens emit transient messages through AppSnackbar.
    val snackbarHostState = remember { SnackbarHostState() }
    LaunchedEffect(Unit) {
        AppSnackbar.messages.collect { snackbarHostState.showSnackbar(it) }
    }
    var showSupportSheet by remember { mutableStateOf(false) }
    var showCreateOrderSheet by remember { mutableStateOf(false) }

    fun navigateTo(route: String) {
        navController.navigate(route) {
            popUpTo(navController.graph.findStartDestination().id) { saveState = true }
            launchSingleTop = true
            restoreState = true
        }
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        gesturesEnabled = showBars,
        drawerContent = {
            ModalDrawerSheet {
                Column(Modifier.fillMaxSize()) {
                    Column(
                        Modifier
                            .weight(1f)
                            .verticalScroll(rememberScrollState()),
                    ) {
                        // Header: the user's avatar, like the web mobile sidebar.
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(horizontal = 28.dp, vertical = 20.dp),
                        ) {
                            val avatarId = appViewModel.session.avatarId
                            if (avatarId != null) {
                                AsyncImage(
                                    model = "${BuildConfig.BASE_URL}/api/avatar/$avatarId.png?size=96&v=3",
                                    contentDescription = "Avatar $avatarId",
                                    modifier = Modifier.size(40.dp),
                                )
                                Spacer(Modifier.width(12.dp))
                                Text(avatarId, style = MaterialTheme.typography.titleMedium)
                            }
                        }
                        pages.forEach { page ->
                            // "Open Ticket" sits between Recovery and Settings,
                            // like the web sidebar; it opens a sheet, not a page.
                            if (page.route == Routes.SETTINGS) {
                                NavigationDrawerItem(
                                    label = { Text("Open Ticket") },
                                    icon = { Icon(Icons.Default.SupportAgent, null) },
                                    selected = false,
                                    onClick = {
                                        scope.launch { drawerState.close() }
                                        showSupportSheet = true
                                    },
                                    modifier = Modifier.padding(horizontal = 12.dp),
                                )
                            }
                            NavigationDrawerItem(
                                label = { Text(page.label) },
                                icon = page.icon,
                                selected = currentRoute == page.route,
                                onClick = {
                                    scope.launch { drawerState.close() }
                                    navigateTo(page.route)
                                },
                                modifier = Modifier.padding(horizontal = 12.dp),
                            )
                        }
                        NavigationDrawerItem(
                            label = { Text("Logout") },
                            icon = { Icon(Icons.AutoMirrored.Filled.Logout, null) },
                            selected = false,
                            onClick = {
                                scope.launch { drawerState.close() }
                                appViewModel.logout()
                            },
                            modifier = Modifier.padding(horizontal = 12.dp),
                        )
                        Spacer(Modifier.height(16.dp))
                    }
                    // Footer logo, same asset as the web mobile sidebar.
                    Image(
                        painter = painterResource(
                            if (isSystemInDarkTheme()) R.drawable.logo_bvb_p2p_light
                            else R.drawable.logo_bvb_p2p_dark
                        ),
                        contentDescription = "Bitcoin Voucher Bot P2P",
                        modifier = Modifier
                            .fillMaxWidth(0.6f)
                            .align(Alignment.CenterHorizontally)
                            .padding(bottom = 24.dp),
                    )
                }
            }
        },
    ) {
    Scaffold(
        // Include the soft keyboard in the content insets: every screen gets
        // resized above the IME through the Scaffold padding, and Compose
        // scrolls the focused text field into view automatically.
        contentWindowInsets = ScaffoldDefaults.contentWindowInsets.union(WindowInsets.ime),
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            if (showBars) {
                TopAppBar(
                    title = {
                        Text(
                            pages.firstOrNull { it.route == currentRoute }?.label
                                ?: if (currentRoute == Routes.NOTIFICATIONS) "Alerts" else ""
                        )
                    },
                    navigationIcon = {
                        IconButton(onClick = { scope.launch { drawerState.open() } }) {
                            Icon(Icons.Default.Menu, contentDescription = "Menu")
                        }
                    },
                    actions = {
                        IconButton(onClick = { navigateTo(Routes.NOTIFICATIONS) }) {
                            Icon(Icons.Default.Notifications, contentDescription = "Alerts")
                        }
                    },
                )
            }
        },
        bottomBar = {
            if (showBars) {
                NavigationBar {
                    NavigationBarItem(
                        selected = currentRoute == Routes.MARKETPLACE,
                        onClick = { navigateTo(Routes.MARKETPLACE) },
                        icon = { Icon(Icons.AutoMirrored.Filled.ShowChart, null) },
                        label = { Text("Market") },
                    )
                    Box(
                        modifier = Modifier.weight(1f),
                        contentAlignment = Alignment.Center,
                    ) {
                        FloatingActionButton(
                            onClick = { showCreateOrderSheet = true },
                            shape = CircleShape,
                            containerColor = MaterialTheme.colorScheme.primary,
                            contentColor = MaterialTheme.colorScheme.onPrimary,
                            elevation = FloatingActionButtonDefaults.elevation(
                                defaultElevation = 8.dp,
                                pressedElevation = 12.dp,
                            ),
                            modifier = Modifier.size(64.dp),
                        ) {
                            Icon(
                                Icons.Default.Add,
                                contentDescription = "Create order",
                                modifier = Modifier.size(30.dp),
                            )
                        }
                    }
                    NavigationBarItem(
                        selected = currentRoute == Routes.TRADES,
                        onClick = { navigateTo(Routes.TRADES) },
                        icon = { Icon(Icons.Default.SwapHoriz, null) },
                        label = { Text("Trades") },
                    )
                }
            }
        }
    ) { padding ->
        NavHost(
            navController = navController,
            startDestination = if (isLoggedIn) Routes.MARKETPLACE else Routes.LOGIN,
            // consumeWindowInsets marks the insets handled by this Scaffold as
            // consumed, so screens with their own imePadding (e.g. the chats)
            // don't apply the keyboard inset a second time.
            modifier = Modifier.padding(padding).consumeWindowInsets(padding),
        ) {
            composable(Routes.LOGIN) {
                LoginScreen(
                    onLoggedIn = {
                        navController.navigate(Routes.MARKETPLACE) {
                            popUpTo(Routes.LOGIN) { inclusive = true }
                        }
                    },
                    onCreateAvatar = { navController.navigate(Routes.CREATE_AVATAR) },
                )
            }
            composable(Routes.CREATE_AVATAR) {
                CreateAvatarScreen(onDone = { navController.popBackStack() })
            }
            composable(Routes.MARKETPLACE) {
                MarketplaceScreen(
                    onOrderClick = { orderId, avatar ->
                        navController.navigate(Routes.orderDetail(orderId, avatar))
                    },
                )
            }
            composable(
                Routes.ORDER_DETAIL,
                arguments = listOf(
                    navArgument("avatar") {
                        type = NavType.StringType
                        nullable = true
                        defaultValue = null
                    },
                ),
            ) {
                OrderDetailScreen(
                    onTradeStarted = { tradeId ->
                        navController.navigate(Routes.tradeDetail(tradeId)) {
                            popUpTo(Routes.MARKETPLACE)
                        }
                    },
                    onBack = { navController.popBackStack() },
                )
            }
            composable(Routes.TRADES) {
                TradesScreen(onTradeClick = { navController.navigate(Routes.tradeDetail(it)) })
            }
            composable(Routes.TRADE_DETAIL) {
                TradeDetailScreen(
                    onOpenChat = { navController.navigate(Routes.chat(it)) },
                    onBack = { navController.popBackStack() },
                )
            }
            composable(Routes.CHAT) {
                ChatScreen(
                    onBack = { navController.popBackStack() },
                    onOpenTrade = { tradeId -> navController.navigate(Routes.tradeDetail(tradeId)) },
                )
            }
            composable(Routes.NOTIFICATIONS) {
                NotificationsScreen()
            }
            composable(Routes.MY_ORDERS) {
                MyOrdersScreen(onOrderClick = { navController.navigate(Routes.orderDetail(it)) })
            }
            composable(Routes.MESSAGES) {
                MessagesScreen(
                    onOpenChat = { navController.navigate(Routes.chat(it)) },
                    onOpenSupport = { navController.navigate(Routes.supportChat(it)) },
                )
            }
            composable(Routes.SUPPORT_CHAT) {
                SupportChatScreen(onBack = { navController.popBackStack() })
            }
            composable(Routes.SERVICES) { ServicesScreen() }
            composable(Routes.DISPUTES) {
                DisputesScreen(
                    onDisputeClick = { navController.navigate(Routes.disputeDetail(it)) },
                )
            }
            composable(Routes.DISPUTE_DETAIL) {
                DisputeDetailScreen(onBack = { navController.popBackStack() })
            }
            composable(Routes.RECOVERY) { RecoveryScreen() }
            composable(Routes.LEARN) { LearnScreen() }
            composable(Routes.SETTINGS) { SettingsScreen() }
        }
        if (showCreateOrderSheet) {
            CreateOrderSheet(onDismiss = { showCreateOrderSheet = false })
        }
        if (showSupportSheet) {
            SupportTicketSheet(
                onOpened = { ticketId ->
                    showSupportSheet = false
                    navController.navigate(Routes.supportChat(ticketId))
                },
                onDismiss = { showSupportSheet = false },
            )
        }
    }
    }
}
