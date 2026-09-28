package com.bvb.android.feature.auth

import androidx.compose.foundation.Image
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.autofill.ContentType
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentType
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bvb.android.BuildConfig
import com.bvb.android.ui.components.LoadingButton
import com.bvb.android.R
import com.bvb.android.core.network.ApiError
import com.bvb.android.data.repository.AuthRepository
import com.bvb.android.ui.components.ErrorBanner
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

data class LoginUiState(
    val loading: Boolean = false,
    val error: String? = null,
    val migratedMnemonic: String? = null,
    val loggedIn: Boolean = false,
)

@HiltViewModel
class LoginViewModel @Inject constructor(
    private val authRepository: AuthRepository,
) : ViewModel() {
    val uiState = MutableStateFlow(LoginUiState())

    fun login(avatarId: String, password: String, turnstileToken: String?) {
        if (avatarId.length != 10) {
            uiState.value = uiState.value.copy(error = "Avatar ID must be 10 characters")
            return
        }
        if (password.length < 8) {
            uiState.value = uiState.value.copy(error = "Password too short (min 8 characters)")
            return
        }
        uiState.value = uiState.value.copy(loading = true, error = null)
        viewModelScope.launch {
            try {
                val result = authRepository.login(avatarId.lowercase(), password, turnstileToken)
                uiState.value = if (result.migratedMnemonic != null) {
                    uiState.value.copy(loading = false, migratedMnemonic = result.migratedMnemonic)
                } else {
                    uiState.value.copy(loading = false, loggedIn = true)
                }
            } catch (e: Exception) {
                uiState.value = uiState.value.copy(loading = false, error = ApiError.messageOf(e))
            }
        }
    }

    fun acknowledgeMnemonic() {
        uiState.value = uiState.value.copy(migratedMnemonic = null, loggedIn = true)
    }
}

@Composable
fun LoginScreen(
    onLoggedIn: () -> Unit,
    onCreateAvatar: () -> Unit,
    viewModel: LoginViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsState()
    var avatarId by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var showPassword by remember { mutableStateOf(false) }
    var turnstileToken by remember { mutableStateOf<String?>(null) }
    var turnstileResetKey by remember { mutableStateOf(0) }

    // Turnstile tokens are single-use: a failed attempt consumed the token,
    // so reset the widget to get a fresh one (like the web client does).
    LaunchedEffect(state.error) {
        if (state.error != null && BuildConfig.TURNSTILE_SITE_KEY.isNotEmpty()) {
            turnstileToken = null
            turnstileResetKey++
        }
    }

    if (state.loggedIn) {
        onLoggedIn()
        return
    }

    state.migratedMnemonic?.let { mnemonic ->
        MnemonicDialog(
            title = "Wallet upgraded",
            mnemonic = mnemonic,
            onConfirm = { viewModel.acknowledgeMnemonic() },
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        // Same logo as the web mobile sidebar; it already includes the
        // "P2P Lightning Marketplace" tagline.
        Image(
            painter = painterResource(
                if (isSystemInDarkTheme()) R.drawable.logo_bvb_p2p_light else R.drawable.logo_bvb_p2p_dark
            ),
            contentDescription = "Bitcoin Voucher Bot P2P",
            modifier = Modifier.fillMaxWidth(0.8f),
        )
        Spacer(Modifier.height(10.dp))
        Text(
            "BETA · v${BuildConfig.VERSION_NAME}",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.height(32.dp))

        state.error?.let {
            ErrorBanner(it)
            Spacer(Modifier.height(16.dp))
        }

        OutlinedTextField(
            value = avatarId,
            onValueChange = { avatarId = it.trim().lowercase() },
            label = { Text("Avatar ID") },
            singleLine = true,
            modifier = Modifier
                .fillMaxWidth()
                .semantics { contentType = ContentType.Username },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
        )
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            value = password,
            onValueChange = { password = it },
            label = { Text("Password") },
            singleLine = true,
            modifier = Modifier
                .fillMaxWidth()
                .semantics { contentType = ContentType.Password },
            visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            trailingIcon = {
                IconButton(onClick = { showPassword = !showPassword }) {
                    Icon(
                        if (showPassword) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                        contentDescription = "Toggle password visibility",
                    )
                }
            },
        )

        if (BuildConfig.TURNSTILE_SITE_KEY.isNotEmpty()) {
            Spacer(Modifier.height(16.dp))
            TurnstileWebView(
                siteKey = BuildConfig.TURNSTILE_SITE_KEY,
                onToken = { turnstileToken = it },
                resetKey = turnstileResetKey,
            )
        }

        Spacer(Modifier.height(24.dp))
        LoadingButton(
            onClick = { viewModel.login(avatarId, password, turnstileToken) },
            loading = state.loading,
            enabled = BuildConfig.TURNSTILE_SITE_KEY.isEmpty() || turnstileToken != null,
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Log in") }
        Spacer(Modifier.height(8.dp))
        TextButton(onClick = onCreateAvatar) {
            Text("New here? Create an avatar")
        }
    }
}
