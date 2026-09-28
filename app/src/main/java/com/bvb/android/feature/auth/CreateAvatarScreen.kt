package com.bvb.android.feature.auth

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentType
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.compose.AsyncImage
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bvb.android.BuildConfig
import com.bvb.android.ui.components.LoadingButton
import com.bvb.android.core.network.ApiError
import com.bvb.android.data.repository.AuthRepository
import com.bvb.android.ui.components.ErrorBanner
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

data class CreateAvatarUiState(
    val loading: Boolean = false,
    val error: String? = null,
    val avatarId: String? = null,
    val mnemonic: String? = null,
)

@HiltViewModel
class CreateAvatarViewModel @Inject constructor(
    private val authRepository: AuthRepository,
) : ViewModel() {
    val uiState = MutableStateFlow(CreateAvatarUiState())

    fun create(password: String, confirm: String, turnstileToken: String?) {
        if (password.length < 12) {
            uiState.value = uiState.value.copy(error = "Password must be at least 12 characters")
            return
        }
        if (password != confirm) {
            uiState.value = uiState.value.copy(error = "Passwords do not match")
            return
        }
        uiState.value = uiState.value.copy(loading = true, error = null)
        viewModelScope.launch {
            try {
                val resp = authRepository.createAvatar(password, "", turnstileToken)
                uiState.value = uiState.value.copy(
                    loading = false,
                    avatarId = resp.user.avatarId,
                    mnemonic = resp.mnemonic,
                )
            } catch (e: Exception) {
                uiState.value = uiState.value.copy(loading = false, error = ApiError.messageOf(e))
            }
        }
    }
}

/** Shows a recovery mnemonic that the user must acknowledge before continuing. */
@Composable
fun MnemonicDialog(title: String, mnemonic: String, onConfirm: () -> Unit, avatarId: String? = null) {
    Dialog(
        onDismissRequest = { /* must acknowledge explicitly */ },
        properties = DialogProperties(
            dismissOnBackPress = false,
            dismissOnClickOutside = false,
            usePlatformDefaultWidth = false,
        ),
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.surface,
        ) {
            Column(
                modifier = Modifier
                    .verticalScroll(rememberScrollState())
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(title, style = MaterialTheme.typography.headlineSmall)

                avatarId?.let { id ->
                    Spacer(Modifier.height(16.dp))
                    AsyncImage(
                        model = "${BuildConfig.BASE_URL}/api/avatar/$id.png?size=256&v=3",
                        contentDescription = "Avatar $id",
                        modifier = Modifier
                            .size(96.dp)
                            .clip(CircleShape),
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        id,
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.primary,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        "This is your avatar ID: write it down, it is your username.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                }

                Spacer(Modifier.height(16.dp))
                Text(
                    "Write down these 12 words. They are the ONLY way to recover your wallet funds; they will never be shown again.",
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(16.dp))

                val words = mnemonic.trim().split(Regex("\\s+"))
                SelectionContainer {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        words.chunked(3).forEachIndexed { rowIndex, rowWords ->
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                rowWords.forEachIndexed { colIndex, word ->
                                    val number = rowIndex * 3 + colIndex + 1
                                    Row(
                                        modifier = Modifier
                                            .weight(1f)
                                            .background(
                                                MaterialTheme.colorScheme.surfaceVariant,
                                                RoundedCornerShape(8.dp),
                                            )
                                            .padding(vertical = 10.dp, horizontal = 8.dp),
                                        horizontalArrangement = Arrangement.Center,
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Text(
                                            "$number. ",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                        Text(
                                            word,
                                            style = MaterialTheme.typography.bodyMedium,
                                            fontWeight = FontWeight.SemiBold,
                                            fontFamily = FontFamily.Monospace,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                Spacer(Modifier.height(24.dp))
                Button(onClick = onConfirm, modifier = Modifier.fillMaxWidth()) {
                    Text("I HAVE SAVED MY RECOVERY PHRASE", fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@Composable
fun CreateAvatarScreen(
    onDone: () -> Unit,
    viewModel: CreateAvatarViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsState()
    var password by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
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

    state.mnemonic?.let { mnemonic ->
        MnemonicDialog(
            title = "Avatar created",
            mnemonic = mnemonic,
            avatarId = state.avatarId,
            onConfirm = onDone,
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
    ) {
        Text("Create avatar", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(8.dp))
        Text(
            "No email, no phone. You get a random avatar identity and a self-custodial Bitcoin & Liquid wallet. Choose a strong password: it protects your wallet keys.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(24.dp))

        state.error?.let {
            ErrorBanner(it)
            Spacer(Modifier.height(16.dp))
        }

        OutlinedTextField(
            value = password,
            onValueChange = { password = it },
            label = { Text("Password (min 12 characters)") },
            singleLine = true,
            modifier = Modifier
                .fillMaxWidth()
                .semantics { contentType = ContentType.NewPassword },
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        )
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            value = confirm,
            onValueChange = { confirm = it },
            label = { Text("Confirm password") },
            singleLine = true,
            modifier = Modifier
                .fillMaxWidth()
                .semantics { contentType = ContentType.NewPassword },
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
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
            onClick = { viewModel.create(password, confirm, turnstileToken) },
            loading = state.loading,
            enabled = BuildConfig.TURNSTILE_SITE_KEY.isEmpty() || turnstileToken != null,
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Create avatar") }
    }
}
