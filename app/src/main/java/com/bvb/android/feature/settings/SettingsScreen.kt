package com.bvb.android.feature.settings

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.fragment.app.FragmentActivity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import coil.compose.AsyncImage
import com.bvb.android.BuildConfig
import com.bvb.android.core.network.ApiError
import com.bvb.android.core.network.ApiService
import com.bvb.android.core.security.BiometricUnlock
import com.bvb.android.core.security.promptBiometric
import com.bvb.android.core.session.SessionManager
import com.bvb.android.core.sse.SseClient
import com.bvb.android.data.repository.AuthRepository
import com.bvb.android.data.model.AffiliateStats
import com.bvb.android.data.model.BlockUserRequest
import com.bvb.android.data.model.BlocklistEntry
import com.bvb.android.data.model.PasswordRequest
import com.bvb.android.data.model.TelegramPreferencesRequest
import com.bvb.android.data.model.TelegramStatus
import com.bvb.android.data.model.TelegramToken
import com.bvb.android.data.model.UserProfile
import com.bvb.android.data.model.UserStats
import com.bvb.android.ui.components.AppSnackbar
import com.bvb.android.ui.components.rememberBiometricAction
import com.bvb.android.ui.components.ErrorBanner
import com.bvb.android.ui.components.FullScreenLoading
import com.bvb.android.ui.components.LoadingOutlinedButton
import com.bvb.android.ui.components.QrCode
import com.bvb.android.ui.components.formatSats
import com.bvb.android.ui.components.passwordContentType
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

private const val WEB_REF_BASE = "https://p2p.bitcoinvoucher.bot/?ref="
private const val ONION_REF_BASE =
    "http://umembxtpokml6fkogemcfnpyt3qqvyw6u3hnvwinevo3gvoe6j7vfyad.onion/?ref="

data class SettingsUiState(
    val loading: Boolean = true,
    val error: String? = null,
    val profile: UserProfile? = null,
    val stats: UserStats? = null,
    val telegram: TelegramStatus? = null,
    val telegramToken: TelegramToken? = null,
    val telegramBusy: Boolean = false,
    val affiliate: AffiliateStats? = null,
    val generatingCode: Boolean = false,
    val showReferralDialog: Boolean = false,
    val blocklist: List<BlocklistEntry> = emptyList(),
    val blockingUser: Boolean = false,
    // Biometric unlock
    val biometricAvailable: Boolean = false,
    val biometricEnabled: Boolean = false,
    // Secret reveal flows
    val askPasswordFor: SecretKind? = null,
    val revealedMnemonic: String? = null,
    val pgpBackup: PgpBackup? = null,
    /** Transient result/info message (mirrors the web toasts). */
    val message: String? = null,
)

enum class SecretKind { MNEMONIC, PGP_KEY }

data class PgpBackup(val publicKey: String, val privateKey: String, val passphrase: String)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val api: ApiService,
    val session: SessionManager,
    private val sse: SseClient,
    val biometric: BiometricUnlock,
    private val authRepository: AuthRepository,
) : ViewModel() {
    val uiState = MutableStateFlow(SettingsUiState())

    init {
        refreshBiometricState()
        load()
        viewModelScope.launch {
            sse.events.collect { event ->
                when (event.type) {
                    "reputation_changed", "user_status_changed", "user_banned" -> loadStats()
                    "user_referred", "commission_received" -> loadAffiliate()
                    "telegram_connected" -> {
                        loadTelegram()
                        loadAffiliate()
                        uiState.update { it.copy(
                            telegramToken = null,
                            message = "Telegram connected successfully!",
                        ) }
                    }
                }
            }
        }
    }

    fun load() {
        viewModelScope.launch {
            try {
                val profile = api.getProfile()
                uiState.update { it.copy(loading = false, profile = profile, error = null) }
                loadStats()
                loadTelegram()
                loadAffiliate()
                loadBlocklist()
            } catch (e: Exception) {
                uiState.update { it.copy(loading = false, error = ApiError.messageOf(e)) }
            }
        }
    }

    private fun loadStats() {
        viewModelScope.launch {
            try {
                val userId = uiState.value.profile?.id ?: session.userId ?: return@launch
                val stats = api.getUserStats(userId)
                uiState.update { it.copy(stats = stats) }
            } catch (e: Exception) {
                // Stats are non-critical; show the rest of the page anyway.
            }
        }
    }

    private fun loadTelegram() {
        viewModelScope.launch {
            try {
                val status = api.getTelegramStatus()
                uiState.update { it.copy(telegram = status) }
            } catch (e: Exception) {
                // Non-critical.
            }
        }
    }

    private fun loadAffiliate() {
        viewModelScope.launch {
            try {
                val stats = api.getAffiliateStats()
                uiState.update { it.copy(
                    affiliate = if (stats.eligible == false) null else stats,
                ) }
            } catch (e: Exception) {
                uiState.update { it.copy(affiliate = null) }
            }
        }
    }

    private fun loadBlocklist() {
        viewModelScope.launch {
            try {
                val blocklist = api.getBlocklist().orEmpty()
                uiState.update { it.copy(blocklist = blocklist) }
            } catch (e: Exception) {
                // Non-critical.
            }
        }
    }

    // --- Telegram ---

    fun connectTelegram() {
        uiState.update { it.copy(telegramBusy = true) }
        viewModelScope.launch {
            try {
                val token = api.generateTelegramToken()
                uiState.update { it.copy(telegramBusy = false, telegramToken = token) }
            } catch (e: Exception) {
                uiState.update { it.copy(
                    telegramBusy = false, message = ApiError.messageOf(e),
                ) }
            }
        }
    }

    fun dismissTelegramDialog() {
        uiState.update { it.copy(telegramToken = null) }
        // The user may have completed the /connect flow in Telegram.
        loadTelegram()
        loadAffiliate()
    }

    fun disableTelegram() {
        uiState.update { it.copy(telegramBusy = true) }
        viewModelScope.launch {
            try {
                api.disableTelegram()
                uiState.update { it.copy(
                    telegramBusy = false,
                    affiliate = null,
                    message = "Telegram notifications disabled",
                ) }
                loadTelegram()
            } catch (e: Exception) {
                uiState.update { it.copy(
                    telegramBusy = false, message = ApiError.messageOf(e),
                ) }
            }
        }
    }

    fun toggleNewOrderNotifications(enabled: Boolean) {
        val current = uiState.value.telegram ?: return
        uiState.update { it.copy(telegram = current.copy(notifyNewOrders = enabled)) }
        viewModelScope.launch {
            try {
                api.updateTelegramPreferences(TelegramPreferencesRequest(enabled))
                uiState.update { it.copy(
                    message = if (enabled) "New order notifications enabled"
                    else "New order notifications disabled",
                ) }
            } catch (e: Exception) {
                // Roll back the optimistic flip.
                uiState.update { it.copy(
                    telegram = current, message = ApiError.messageOf(e),
                ) }
            }
        }
    }

    // --- Affiliate ---

    fun generateReferralCode() {
        if (uiState.value.telegram?.telegramEnabled != true) {
            uiState.update { it.copy(
                message = "You need to connect Telegram to generate a referral code",
            ) }
            return
        }
        uiState.update { it.copy(generatingCode = true) }
        viewModelScope.launch {
            try {
                api.generateReferralCode()
                uiState.update { it.copy(
                    generatingCode = false, message = "Referral code generated successfully",
                ) }
                loadAffiliate()
            } catch (e: Exception) {
                uiState.update { it.copy(
                    generatingCode = false, message = ApiError.messageOf(e),
                ) }
            }
        }
    }

    fun setShowReferralDialog(show: Boolean) {
        uiState.update { it.copy(showReferralDialog = show) }
    }

    // --- Blocklist ---

    fun blockUser(avatarId: String) {
        val target = avatarId.trim()
        if (target.isEmpty()) return
        uiState.update { it.copy(blockingUser = true) }
        viewModelScope.launch {
            try {
                api.blockUser(BlockUserRequest(target))
                uiState.update { it.copy(
                    blockingUser = false, message = "$target has been blocked",
                ) }
                loadBlocklist()
            } catch (e: Exception) {
                uiState.update { it.copy(
                    blockingUser = false, message = ApiError.messageOf(e),
                ) }
            }
        }
    }

    fun unblockUser(avatarId: String) {
        viewModelScope.launch {
            try {
                api.unblockUser(avatarId)
                uiState.update { it.copy(message = "$avatarId has been unblocked") }
                loadBlocklist()
            } catch (e: Exception) {
                uiState.update { it.copy(message = ApiError.messageOf(e)) }
            }
        }
    }

    // --- Secrets ---

    fun requestSecret(kind: SecretKind) {
        uiState.update { it.copy(askPasswordFor = kind) }
    }

    fun revealSecret(kind: SecretKind, password: String) {
        uiState.update { it.copy(askPasswordFor = null) }
        viewModelScope.launch {
            try {
                when (kind) {
                    SecretKind.MNEMONIC -> {
                        val mnemonic = api.revealMnemonic(PasswordRequest(password)).mnemonic
                        uiState.update { it.copy(revealedMnemonic = mnemonic) }
                    }
                    SecretKind.PGP_KEY -> {
                        val keys = api.revealPgpKeys(PasswordRequest(password))
                        val avatar = session.avatarId.orEmpty()
                        uiState.update { it.copy(
                            pgpBackup = PgpBackup(
                                publicKey = keys.pgpPublicKey,
                                privateKey = keys.pgpPrivateKey,
                                passphrase = "$avatar:$password",
                            ),
                        ) }
                    }
                }
            } catch (e: Exception) {
                uiState.update { it.copy(message = ApiError.messageOf(e)) }
            }
        }
    }

    fun hideSecrets() {
        uiState.update { it.copy(revealedMnemonic = null, pgpBackup = null) }
    }

    fun dismissPasswordPrompt() {
        uiState.update { it.copy(askPasswordFor = null) }
    }

    fun consumeMessage() {
        uiState.update { it.copy(message = null) }
    }

    // --- Biometric unlock ---

    fun refreshBiometricState() {
        uiState.update {
            it.copy(
                biometricAvailable = biometric.canAuthenticate(),
                biometricEnabled = biometric.isEnabled,
            )
        }
    }

    /** Persists the biometric-encrypted password after a successful enroll auth. */
    fun completeBiometricEnroll(cipher: javax.crypto.Cipher, password: String) {
        runCatching { biometric.persist(cipher, password, session.avatarId.orEmpty()) }
            .onSuccess {
                uiState.update {
                    it.copy(biometricEnabled = true, message = "App lock enabled")
                }
            }
            .onFailure {
                uiState.update { it.copy(message = "Could not enable app lock") }
            }
    }

    fun disableBiometric() {
        biometric.disable()
        uiState.update { it.copy(biometricEnabled = false, message = "App lock disabled") }
    }

    /**
     * Verifies the password against the backend (and restores the in-memory
     * session) so a fresh biometric enrollment can bind a known-good password.
     */
    suspend fun verifyPassword(password: String): Boolean =
        runCatching { authRepository.unlockPgpKeys(password) }.isSuccess
}

@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // Re-check biometric capability/enrollment whenever the screen is shown
    // (the user may have changed device settings while the app was backgrounded).
    LaunchedEffect(Unit) { viewModel.refreshBiometricState() }

    var showBiometricPasswordSheet by remember { mutableStateOf(false) }

    fun enrollBiometric(password: String) {
        val activity = context as? FragmentActivity
        val cipher = activity?.let { runCatching { viewModel.biometric.encryptCipher() }.getOrNull() }
        if (activity == null || cipher == null) {
            scope.launch { AppSnackbar.show("Biometric unlock is not available on this device") }
            return
        }
        promptBiometric(
            activity = activity,
            cipher = cipher,
            title = "Enable app lock",
            subtitle = "Confirm to secure your session",
            negativeText = "Cancel",
            onSuccess = { authed -> viewModel.completeBiometricEnroll(authed, password) },
            onError = { msg -> scope.launch { AppSnackbar.show(msg) } },
        )
    }

    fun confirmDisableBiometric() {
        val activity = context as? FragmentActivity
        val cipher = activity?.let { viewModel.biometric.decryptCipher() }
        // If the key was invalidated (e.g. new biometric enrolled) the stored
        // password is already unusable, so allow disabling without a prompt to
        // avoid a lockout. Otherwise require authentication to turn it off.
        if (activity == null || cipher == null) {
            viewModel.disableBiometric()
            return
        }
        promptBiometric(
            activity = activity,
            cipher = cipher,
            title = "Disable app lock",
            subtitle = "Confirm to turn off the app lock",
            negativeText = "Cancel",
            onSuccess = { viewModel.disableBiometric() },
            onError = { msg -> scope.launch { AppSnackbar.show(msg) } },
        )
    }

    if (showBiometricPasswordSheet) {
        PasswordSheet(
            title = "Enable app lock",
            description = "Enter your password once to enable the app lock. Afterwards you can unlock with your fingerprint, face, or device PIN/pattern.",
            confirmLabel = "Continue",
            onConfirm = { pw ->
                showBiometricPasswordSheet = false
                scope.launch {
                    if (viewModel.verifyPassword(pw)) enrollBiometric(pw)
                    else AppSnackbar.show("Wrong password")
                }
            },
            onDismiss = { showBiometricPasswordSheet = false },
        )
    }

    // --- Bottom sheets (same pattern used across the app) ---

    state.askPasswordFor?.let { kind ->
        PasswordSheet(
            title = if (kind == SecretKind.MNEMONIC) "Security Verification" else "View Keys for Backup",
            description = if (kind == SecretKind.MNEMONIC) {
                "Enter your password to decrypt and view your 12-word recovery phrase. Never share these words with anyone."
            } else {
                "Enter your password to decrypt your PGP keys for backup."
            },
            onConfirm = { viewModel.revealSecret(kind, it) },
            onDismiss = { viewModel.dismissPasswordPrompt() },
            biometric = viewModel.biometric,
        )
    }

    state.revealedMnemonic?.let { mnemonic ->
        MnemonicSheet(mnemonic = mnemonic, onDismiss = { viewModel.hideSecrets() })
    }

    state.pgpBackup?.let { backup ->
        PgpBackupSheet(backup = backup, onDismiss = { viewModel.hideSecrets() })
    }

    state.telegramToken?.let { token ->
        TelegramActivationSheet(
            token = token,
            onOpenTelegram = {
                runCatching {
                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(token.botLink)))
                }
            },
            onDismiss = { viewModel.dismissTelegramDialog() },
        )
    }

    if (state.showReferralDialog) {
        state.affiliate?.let { affiliate ->
            ReferralShareSheet(
                affiliate = affiliate,
                onGenerate = { viewModel.generateReferralCode() },
                generating = state.generatingCode,
                onDismiss = { viewModel.setShowReferralDialog(false) },
            )
        }
    }

    // Transient result messages are shown as snackbars via the global host.
    LaunchedEffect(state.message) {
        state.message?.let {
            AppSnackbar.show(it)
            viewModel.consumeMessage()
        }
    }

    // --- Page ---

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
    ) {
        state.error?.let {
            ErrorBanner(it)
            Spacer(Modifier.height(12.dp))
        }

        if (state.loading) {
            FullScreenLoading()
            return@Column
        }

        val profile = state.profile ?: return@Column

        // Profile header: avatar, id, user id, reputation, member since.
        Row(verticalAlignment = Alignment.CenterVertically) {
            AsyncImage(
                model = "${BuildConfig.BASE_URL}/api/avatar/${profile.avatarId}.png?size=128&v=3",
                contentDescription = "Avatar ${profile.avatarId}",
                modifier = Modifier.size(60.dp),
            )
            Spacer(Modifier.width(16.dp))
            Column {
                Text(profile.avatarId, style = MaterialTheme.typography.titleLarge)
                Text(
                    "User ID: ${profile.id}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                val stats = state.stats
                if (stats != null && stats.totalTrades > 0) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        ReputationStars(stats.trustLevel)
                        Spacer(Modifier.width(6.dp))
                        Text(
                            "%.1f%%".format(
                                if (stats.successRate > 0) stats.successRate else stats.tradeSuccessRate
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                } else {
                    Text(
                        "Reputation: —",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                profile.createdAt?.let {
                    Text(
                        "Member since ${formatDate(it)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        HorizontalDivider(Modifier.padding(vertical = 16.dp))

        // --- PGP Encryption Keys ---
        SectionTitle("PGP Encryption Keys")
        StatusLine(
            ok = profile.hasPgpKeys,
            okText = "PGP keys configured",
            koText = "PGP keys will be generated at next login",
        )
        Spacer(Modifier.height(8.dp))
        OutlinedButton(
            onClick = { viewModel.requestSecret(SecretKind.PGP_KEY) },
            enabled = profile.hasPgpKeys,
            modifier = Modifier.fillMaxWidth(),
        ) { Text(if (profile.hasPgpKeys) "View Keys for Backup" else "Keys generated automatically") }
        SectionCaption(
            if (profile.hasPgpKeys) {
                "Your PGP keys enable automatic encrypted messaging. Keys are generated and managed automatically."
            } else {
                "PGP keys will be automatically generated when you next login. These keys enable encrypted messaging during trades."
            }
        )

        HorizontalDivider(Modifier.padding(vertical = 16.dp))

        // --- Telegram Notifications ---
        SectionTitle("Telegram Notifications")
        val telegram = state.telegram
        StatusLine(
            ok = telegram?.telegramEnabled == true,
            okText = "Connected",
            koText = "Not connected",
        )
        if (telegram?.telegramEnabled == true) {
            Spacer(Modifier.height(4.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Notify new orders", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        "Receive a Telegram notification when a new order is published in the marketplace",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = telegram.notifyNewOrders,
                    onCheckedChange = { viewModel.toggleNewOrderNotifications(it) },
                )
            }
            Spacer(Modifier.height(8.dp))
            OutlinedButton(
                onClick = { viewModel.disableTelegram() },
                enabled = !state.telegramBusy,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Disable") }
        } else {
            Spacer(Modifier.height(8.dp))
            LoadingOutlinedButton(
                onClick = { viewModel.connectTelegram() },
                loading = state.telegramBusy,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Connect Telegram") }
        }

        HorizontalDivider(Modifier.padding(vertical = 16.dp))

        // --- Wallet Recovery ---
        SectionTitle("Wallet Recovery")
        SectionCaption("View your 12-word recovery phrase to back up your wallet.")
        Spacer(Modifier.height(8.dp))
        OutlinedButton(
            onClick = { viewModel.requestSecret(SecretKind.MNEMONIC) },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Show Recovery Phrase") }

        // --- Security / Biometric unlock ---
        if (state.biometricAvailable) {
            HorizontalDivider(Modifier.padding(vertical = 16.dp))
            SectionTitle("Security")
            Spacer(Modifier.height(4.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(Modifier.weight(1f)) {
                    Text("App lock", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        "Unlock the app and restore your session with your fingerprint, face, or device PIN/pattern instead of typing your password.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = state.biometricEnabled,
                    onCheckedChange = { want ->
                        if (!want) {
                            confirmDisableBiometric()
                        } else {
                            // Defense in depth: always require re-typing and
                            // verifying the account password to enable the App
                            // lock, even when it's still in memory. The biometric
                            // confirmation then happens inside enrollBiometric().
                            showBiometricPasswordSheet = true
                        }
                    },
                )
            }
        }

        HorizontalDivider(Modifier.padding(vertical = 16.dp))

        // --- Blocked Users ---
        SectionTitle("Blocked Users")
        var blockAvatarId by remember { mutableStateOf("") }
        OutlinedTextField(
            value = blockAvatarId,
            onValueChange = { blockAvatarId = it.trim().lowercase() },
            placeholder = { Text("Enter avatar ID to block…") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(8.dp))
        LoadingOutlinedButton(
            onClick = {
                viewModel.blockUser(blockAvatarId)
                blockAvatarId = ""
            },
            loading = state.blockingUser,
            enabled = blockAvatarId.isNotBlank(),
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Block") }
        SectionCaption("Blocked users cannot see your orders or interact with them.")
        state.blocklist.forEach { entry ->
            Spacer(Modifier.height(8.dp))
            Card {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                ) {
                    AsyncImage(
                        model = "${BuildConfig.BASE_URL}/api/avatar/${entry.blockedAvatarId}.png?size=64&v=3",
                        contentDescription = null,
                        modifier = Modifier.size(28.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    Column(Modifier.weight(1f)) {
                        Text(entry.blockedAvatarId, style = MaterialTheme.typography.bodyMedium)
                        entry.createdAt?.let {
                            Text(
                                formatDate(it),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    TextButton(onClick = { viewModel.unblockUser(entry.blockedAvatarId) }) {
                        Text("Unblock")
                    }
                }
            }
        }

        // --- Affiliate Program (only for affiliates, like the web) ---
        state.affiliate?.let { affiliate ->
            HorizontalDivider(Modifier.padding(vertical = 16.dp))
            SectionTitle("Affiliate Program")
            Spacer(Modifier.height(4.dp))
            StatRow("Referral Code", affiliate.currentCode.ifEmpty { "None" })
            StatRow("Referred Users", affiliate.referredUsers.toString())
            StatRow("Pending Balance", formatSats(affiliate.pendingBalance))
            StatRow("Min Payout", formatSats(affiliate.minPayoutSats))
            Spacer(Modifier.height(8.dp))
            if (affiliate.currentCode.isEmpty()) {
                LoadingOutlinedButton(
                    onClick = { viewModel.generateReferralCode() },
                    loading = state.generatingCode,
                    enabled = state.telegram?.telegramEnabled == true,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        if (state.telegram?.telegramEnabled != true) "Connect Telegram first"
                        else "Generate Referral Code"
                    )
                }
            } else {
                OutlinedButton(
                    onClick = { viewModel.setShowReferralDialog(true) },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Share Referral Link") }
            }
        }

        HorizontalDivider(Modifier.padding(vertical = 16.dp))
        Text(
            "Bitcoin Voucher Bot · BETA · v${BuildConfig.VERSION_NAME}",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.fillMaxWidth(),
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )

        Spacer(Modifier.height(24.dp))
    }
}

// --- Section helpers ---

@Composable
private fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(text, style = MaterialTheme.typography.titleMedium, modifier = modifier)
}

@Composable
private fun SectionCaption(text: String) {
    Spacer(Modifier.height(6.dp))
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun StatusLine(ok: Boolean, okText: String, koText: String) {
    Spacer(Modifier.height(6.dp))
    Text(
        (if (ok) "✓ " else "✕ ") + if (ok) okText else koText,
        style = MaterialTheme.typography.bodyMedium,
        color = if (ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun StatRow(name: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Text(
            name,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        Text(value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun ReputationStars(level: Int) {
    val lvl = level.coerceIn(0, 5)
    Text(
        buildString {
            repeat(lvl) { append('★') }
            repeat(5 - lvl) { append('☆') }
        },
        color = MaterialTheme.colorScheme.primary,
        style = MaterialTheme.typography.bodyMedium,
    )
}

// --- Bottom sheets ---

/** Shared scaffold for all the Settings bottom sheets. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsSheet(
    title: String,
    onDismiss: () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp)
                .padding(bottom = 24.dp)
                .imePadding(),
        ) {
            Text(title, style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(12.dp))
            content()
        }
    }
}

@Composable
private fun PasswordSheet(
    title: String,
    description: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
    confirmLabel: String = "Reveal",
    biometric: BiometricUnlock? = null,
) {
    var password by remember { mutableStateOf("") }
    val bioAction = biometric?.let {
        rememberBiometricAction(it, title, "Authenticate to continue")
    }
    SettingsSheet(title = title, onDismiss = onDismiss) {
        Text(
            description,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(16.dp))
        if (bioAction != null) {
            // App lock on: the primary button authenticates then acts; no field.
            Button(
                onClick = { bioAction { onConfirm(it) } },
                modifier = Modifier.fillMaxWidth(),
            ) { Text(confirmLabel) }
        } else {
            OutlinedTextField(
                value = password,
                onValueChange = { password = it },
                label = { Text("Password") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                modifier = Modifier.fillMaxWidth().passwordContentType(),
            )
            Spacer(Modifier.height(16.dp))
            Button(
                onClick = { onConfirm(password) },
                enabled = password.length >= 8,
                modifier = Modifier.fillMaxWidth(),
            ) { Text(confirmLabel) }
        }
    }
}

@Composable
private fun MnemonicSheet(mnemonic: String, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val words = mnemonic.trim().split(Regex("\\s+"))
    SettingsSheet(title = "Your Recovery Phrase", onDismiss = onDismiss) {
        Text(
            "Write these words down and store them in a safe place. Anyone with these words can access your funds.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(12.dp))
        LazyVerticalGrid(
            columns = GridCells.Fixed(3),
            verticalArrangement = Arrangement.spacedBy(6.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.height(((words.size + 2) / 3 * 44).dp),
            userScrollEnabled = false,
        ) {
            items(words.withIndex().toList()) { (i, word) ->
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier
                        .background(
                            MaterialTheme.colorScheme.surfaceVariant,
                            RoundedCornerShape(6.dp),
                        )
                        .padding(vertical = 6.dp),
                ) {
                    Text(
                        "${i + 1}. $word",
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                    )
                }
            }
        }
        Spacer(Modifier.height(16.dp))
        OutlinedButton(
            onClick = { copyToClipboard(context, "mnemonic", mnemonic) },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Copy") }
    }
}

@Composable
private fun PgpBackupSheet(backup: PgpBackup, onDismiss: () -> Unit) {
    val context = LocalContext.current
    SettingsSheet(title = "Don't trust, verify", onDismiss = onDismiss) {
        Text(
            "Your communication is end-to-end encrypted with OpenPGP. You can verify the privacy of this chat using any tool based on the OpenPGP standard.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(12.dp))
        CredentialBlock("Your public key", backup.publicKey) {
            copyToClipboard(context, "pgp public key", backup.publicKey)
        }
        Spacer(Modifier.height(10.dp))
        CredentialBlock("Your encrypted private key", backup.privateKey) {
            copyToClipboard(context, "pgp private key", backup.privateKey)
        }
        Spacer(Modifier.height(10.dp))
        CredentialBlock("Your private key passphrase (keep secure!)", backup.passphrase) {
            copyToClipboard(context, "pgp passphrase", backup.passphrase)
        }
    }
}

@Composable
private fun CredentialBlock(label: String, content: String, onCopy: () -> Unit) {
    Column {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Text(
                label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onCopy) { Text("Copy") }
        }
        SelectionContainer {
            Text(
                content,
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 4,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(8.dp))
                    .padding(8.dp),
            )
        }
    }
}

@Composable
private fun TelegramActivationSheet(
    token: TelegramToken,
    onOpenTelegram: () -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    SettingsSheet(title = "Connect Telegram", onDismiss = onDismiss) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                "Open the bot and send the command below to connect your account:",
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(Modifier.height(12.dp))
            QrCode(content = token.botLink, sizeDp = 200)
            Spacer(Modifier.height(12.dp))
            SelectionContainer {
                Text(
                    token.command,
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier
                        .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(8.dp))
                        .padding(10.dp),
                )
            }
            TextButton(onClick = { copyToClipboard(context, "telegram command", token.command) }) {
                Text("Copy the command")
            }
            Text(
                "This token will expire in ${token.expiresIn}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(16.dp))
            Button(
                onClick = onOpenTelegram,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Open Telegram") }
        }
    }
}

@Composable
private fun ReferralShareSheet(
    affiliate: AffiliateStats,
    onGenerate: () -> Unit,
    generating: Boolean,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    SettingsSheet(title = "Share Referral Link", onDismiss = onDismiss) {
        if (affiliate.currentCode.isNotEmpty()) {
            Text(
                "Share these links with your friends. When they create an account using your link, you'll earn commission on their trades!",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))
            ReferralLinkBlock("Website", WEB_REF_BASE + affiliate.currentCode) {
                copyToClipboard(context, "referral link", WEB_REF_BASE + affiliate.currentCode)
            }
            affiliate.referralUrl?.let { url ->
                Spacer(Modifier.height(10.dp))
                ReferralLinkBlock("Telegram", url) {
                    copyToClipboard(context, "referral link", url)
                }
            }
            Spacer(Modifier.height(10.dp))
            ReferralLinkBlock("Onion (Tor)", ONION_REF_BASE + affiliate.currentCode) {
                copyToClipboard(context, "referral link", ONION_REF_BASE + affiliate.currentCode)
            }
        } else {
            Text("You don't have a referral code yet.")
            Spacer(Modifier.height(8.dp))
            LoadingOutlinedButton(
                onClick = onGenerate,
                loading = generating,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Generate Referral Code") }
        }
    }
}

@Composable
private fun ReferralLinkBlock(label: String, url: String, onCopy: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
        Text(label, style = MaterialTheme.typography.labelLarge)
        Spacer(Modifier.height(6.dp))
        QrCode(content = url, sizeDp = 160)
        Spacer(Modifier.height(6.dp))
        Text(
            url,
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        TextButton(onClick = onCopy) { Text("Copy") }
    }
}

// --- Helpers ---

private fun copyToClipboard(context: Context, label: String, text: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText(label, text))
}

private fun formatDate(iso: String): String = try {
    DateTimeFormatter.ofPattern("dd MMM yyyy")
        .withZone(ZoneId.systemDefault())
        .format(Instant.parse(iso))
} catch (e: Exception) {
    iso.take(10)
}
