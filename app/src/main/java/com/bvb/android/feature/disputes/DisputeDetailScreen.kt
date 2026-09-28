package com.bvb.android.feature.disputes

import android.util.Base64
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bvb.android.core.network.ApiError
import com.bvb.android.core.network.ApiService
import com.bvb.android.core.pgp.PgpService
import com.bvb.android.core.session.SessionManager
import com.bvb.android.core.sse.SseClient
import com.bvb.android.data.model.ChatEvidenceMessage
import com.bvb.android.data.model.ChatEvidenceRequest
import com.bvb.android.data.model.Dispute
import com.bvb.android.data.model.TextEvidenceRequest
import com.bvb.android.data.repository.AuthRepository
import com.bvb.android.ui.components.AppSheet
import com.bvb.android.ui.components.AppSnackbar
import com.bvb.android.ui.components.ErrorBanner
import com.bvb.android.ui.components.FullScreenLoading
import com.bvb.android.ui.components.LabeledValue
import com.bvb.android.ui.components.LoadingOutlinedButton
import com.bvb.android.ui.components.StatusChip
import com.bvb.android.ui.components.formatDate
import com.bvb.android.ui.components.formatFiat
import com.bvb.android.ui.components.formatSats
import com.bvb.android.ui.components.noAutofill
import com.bvb.android.ui.components.passwordContentType
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.toRequestBody

private val ALLOWED_EVIDENCE_TYPES = setOf(
    "image/jpeg", "image/jpg", "image/png", "application/pdf", "text/plain",
)
private const val MAX_EVIDENCE_BYTES = 4 * 1024 * 1024

data class DisputeDetailUiState(
    val loading: Boolean = true,
    val error: String? = null,
    val dispute: Dispute? = null,
    val submittingEvidence: Boolean = false,
    val evidenceMessage: String? = null,
    /** Chat evidence needs the PGP keys: ask the password if not in memory. */
    val askPasswordForChat: Boolean = false,
)

@HiltViewModel
class DisputeDetailViewModel @Inject constructor(
    private val api: ApiService,
    private val pgp: PgpService,
    val session: SessionManager,
    private val sse: SseClient,
    private val auth: AuthRepository,
    val biometric: com.bvb.android.core.security.BiometricUnlock,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {
    private val disputeId: String = checkNotNull(savedStateHandle["disputeId"])
    val uiState = MutableStateFlow(DisputeDetailUiState())

    init {
        load()
        viewModelScope.launch {
            sse.events.collect { event ->
                when (event.type) {
                    "dispute_created", "dispute_resolved_in_favor", "dispute_resolved_against",
                    "new_evidence_added", "evidence_submitted", "sse_reconnected" -> load()
                }
            }
        }
    }

    fun load() {
        viewModelScope.launch {
            try {
                val dispute = api.getDispute(disputeId)
                uiState.update { it.copy(loading = false, error = null, dispute = dispute) }
            } catch (e: Exception) {
                uiState.update { it.copy(loading = false, error = ApiError.messageOf(e)) }
            }
        }
    }

    fun submitTextEvidence(text: String) {
        submit {
            val encoded = Base64.encodeToString(text.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
            api.addTextEvidence(disputeId, TextEvidenceRequest(evidenceType = "text/plain", content = encoded))
            "Evidence submitted."
        }
    }

    fun submitFileEvidence(fileName: String, mimeType: String, bytes: ByteArray) {
        submit {
            if (mimeType !in ALLOWED_EVIDENCE_TYPES) {
                throw IllegalArgumentException("Unsupported file type. Allowed: JPG, PNG, PDF, TXT.")
            }
            if (bytes.size > MAX_EVIDENCE_BYTES) {
                throw IllegalArgumentException("File too large (max 4 MB).")
            }
            val part = MultipartBody.Part.createFormData(
                "file", fileName, bytes.toRequestBody(mimeType.toMediaTypeOrNull()),
            )
            api.addFileEvidence(disputeId, part)
            "Evidence submitted."
        }
    }

    /** Decrypts the trade chat locally (like the web app) and posts the plaintexts. */
    fun submitChatEvidence() {
        val dispute = uiState.value.dispute ?: return
        // The keys are memory-only: after a process restart they must be
        // unlocked again with the account password.
        if (session.pgpPrivateKeyArmored == null || session.sessionPassword == null) {
            uiState.update { it.copy(askPasswordForChat = true) }
            return
        }
        submit {
            val privateKey = session.pgpPrivateKeyArmored
                ?: throw IllegalStateException("PGP keys unavailable, please log in again.")
            // The key is locked with "avatarId:password", not the bare password.
            val passphrase = session.pgpPassphrase
                ?: throw IllegalStateException("Session password unavailable, please log in again.")
            val myUserId = session.userId.orEmpty()

            val raw = api.getMessages(dispute.tradeId).orEmpty()
            val decrypted = withContext(Dispatchers.Default) {
                raw.filter { it.messageType == "text" }.mapNotNull { msg ->
                    try {
                        val text = if (msg.isEncrypted) {
                            val armored = pgp.extractEncryptionForUser(msg.encryptedText, myUserId, msg.senderId)
                            pgp.decryptMessage(armored, privateKey, passphrase)
                        } else {
                            msg.encryptedText
                        }
                        ChatEvidenceMessage(messageId = msg.id, decryptedText = text)
                    } catch (t: Throwable) {
                        android.util.Log.e("BVB", "evidence decryption failed", t)
                        null
                    }
                }
            }
            if (decrypted.isEmpty()) {
                throw IllegalStateException("No messages could be decrypted for this trade.")
            }
            api.submitChatEvidence(dispute.id, ChatEvidenceRequest(decrypted))
            "Chat submitted as evidence (${decrypted.size} messages)."
        }
    }

    /** Unlocks the PGP keys with the password, then retries the chat evidence. */
    fun unlockAndSubmitChat(password: String) {
        uiState.update { it.copy(askPasswordForChat = false, submittingEvidence = true) }
        viewModelScope.launch {
            try {
                auth.unlockPgpKeys(password)
                uiState.update { it.copy(submittingEvidence = false) }
                submitChatEvidence()
            } catch (t: Throwable) {
                android.util.Log.e("BVB", "pgp unlock failed", t)
                uiState.update {
                    it.copy(submittingEvidence = false, evidenceMessage = ApiError.messageOf(t))
                }
            }
        }
    }

    fun dismissPasswordPrompt() {
        uiState.update { it.copy(askPasswordForChat = false) }
    }

    fun consumeEvidenceMessage() {
        uiState.update { it.copy(evidenceMessage = null) }
    }

    private fun submit(block: suspend () -> String) {
        uiState.update { it.copy(submittingEvidence = true, evidenceMessage = null) }
        viewModelScope.launch {
            try {
                val message = block()
                uiState.update { it.copy(submittingEvidence = false, evidenceMessage = message) }
            } catch (t: Throwable) {
                android.util.Log.e("BVB", "evidence submit failed", t)
                val msg = (t as? IllegalArgumentException)?.message
                    ?: (t as? IllegalStateException)?.message
                    ?: ApiError.messageOf(t)
                uiState.update { it.copy(submittingEvidence = false, evidenceMessage = msg) }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DisputeDetailScreen(
    onBack: () -> Unit,
    viewModel: DisputeDetailViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsState()

    if (state.askPasswordForChat) {
        PasswordPromptSheet(
            biometric = viewModel.biometric,
            onConfirm = { viewModel.unlockAndSubmitChat(it) },
            onDismiss = { viewModel.dismissPasswordPrompt() },
        )
    }

    // Evidence submission results are shown as snackbars via the global host.
    LaunchedEffect(state.evidenceMessage) {
        state.evidenceMessage?.let {
            AppSnackbar.show(it)
            viewModel.consumeEvidenceMessage()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Dispute ${state.dispute?.id?.take(8).orEmpty()}…") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        }
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
        ) {
            state.error?.let { ErrorBanner(it, Modifier.padding(bottom = 12.dp)) }

            val dispute = state.dispute
            if (dispute == null) {
                if (state.loading) FullScreenLoading()
                return@Column
            }

            DisputeDetailContent(
                dispute = dispute,
                myUserId = viewModel.session.userId.orEmpty(),
                submitting = state.submittingEvidence,
                onSubmitText = { viewModel.submitTextEvidence(it) },
                onSubmitFile = { name, mime, bytes -> viewModel.submitFileEvidence(name, mime, bytes) },
                onSubmitChat = { viewModel.submitChatEvidence() },
            )
        }
    }
}

@Composable
private fun PasswordPromptSheet(
    biometric: com.bvb.android.core.security.BiometricUnlock,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var password by remember { mutableStateOf("") }
    AppSheet(title = "Unlock encrypted chat", onDismiss = onDismiss) {
        val bioAction = com.bvb.android.ui.components.rememberBiometricAction(
            biometric, "Unlock encrypted chat", "Authenticate to decrypt the chat",
        )
        Text(
            "Enter your password to decrypt the trade chat before submitting it as evidence.",
            style = MaterialTheme.typography.bodyMedium,
        )
        Spacer(Modifier.height(16.dp))
        if (bioAction != null) {
            Button(
                onClick = { bioAction { onConfirm(it) } },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Unlock and submit") }
        } else {
            OutlinedTextField(
                value = password,
                onValueChange = { password = it },
                placeholder = { Text("Password") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                modifier = Modifier.fillMaxWidth().passwordContentType(),
            )
            Spacer(Modifier.height(20.dp))
            Button(
                onClick = { onConfirm(password) },
                enabled = password.isNotBlank(),
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Unlock and submit") }
        }
        Spacer(Modifier.height(8.dp))
        OutlinedButton(
            onClick = onDismiss,
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Cancel") }
    }
}

@Composable
private fun DisputeDetailContent(
    dispute: Dispute,
    myUserId: String,
    submitting: Boolean,
    onSubmitText: (String) -> Unit,
    onSubmitFile: (String, String, ByteArray) -> Unit,
    onSubmitChat: () -> Unit,
) {
    val iAmBuyer = dispute.buyerId == myUserId
    val iAmParty = iAmBuyer || dispute.sellerId == myUserId
    var showTextEvidence by remember { mutableStateOf(false) }
    var evidenceText by remember { mutableStateOf("") }

    val context = LocalContext.current
    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val resolver = context.contentResolver
        val mime = resolver.getType(uri) ?: "application/octet-stream"
        val bytes = resolver.openInputStream(uri)?.use { it.readBytes() } ?: return@rememberLauncherForActivityResult
        val name = uri.lastPathSegment?.substringAfterLast('/') ?: "evidence"
        onSubmitFile(name, mime, bytes)
    }

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                StatusChip(dispute.status)
                if (dispute.isSystem) {
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "Automatic",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
            LabeledValue("Trade", "${dispute.tradeId.take(8)}…")
            LabeledValue("Your role", if (iAmBuyer) "Buyer" else "Seller")
            LabeledValue("Counterparty", if (iAmBuyer) dispute.sellerAvatar else dispute.buyerAvatar)
            LabeledValue("Amount", formatSats(dispute.tradeAmount))
            if (dispute.tradePrice > 0) {
                LabeledValue("Fiat", formatFiat(dispute.tradePrice, dispute.currencyCode))
            }
            LabeledValue("Opened", formatDate(dispute.createdAt))
            Spacer(Modifier.height(8.dp))
            Text(
                "Reason",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(dispute.reason, style = MaterialTheme.typography.bodyMedium)
        }
    }

    if (dispute.status == "resolved") {
        Spacer(Modifier.height(12.dp))
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Text("Resolution", style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(8.dp))
                if (dispute.resolution.isNotEmpty()) LabeledValue("Resolution", dispute.resolution)
                dispute.resolvedAt?.let { LabeledValue("Resolved on", formatDate(it)) }
                dispute.winnerId?.let { winner ->
                    val label = when (winner) {
                        dispute.buyerId -> "Buyer" + if (iAmBuyer) " (You)" else ""
                        dispute.sellerId -> "Seller" + if (!iAmBuyer && iAmParty) " (You)" else ""
                        else -> winner.take(8)
                    }
                    LabeledValue("Winner", label)
                }
            }
        }
    }

    if (dispute.status == "open" && iAmParty) {
        Spacer(Modifier.height(12.dp))
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Text("Submit evidence", style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(12.dp))
                if (showTextEvidence) {
                    OutlinedTextField(
                        value = evidenceText,
                        onValueChange = { evidenceText = it },
                        placeholder = { Text("Describe your evidence…") },
                        minLines = 3,
                        modifier = Modifier.fillMaxWidth().noAutofill(),
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = {
                                onSubmitText(evidenceText.trim())
                                evidenceText = ""
                                showTextEvidence = false
                            },
                            enabled = !submitting && evidenceText.isNotBlank(),
                        ) { Text("Submit text") }
                        TextButton(onClick = { showTextEvidence = false }) { Text("Cancel") }
                    }
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            onClick = { showTextEvidence = true },
                            enabled = !submitting,
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text("Add text evidence") }
                        OutlinedButton(
                            onClick = { filePicker.launch("*/*") },
                            enabled = !submitting,
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text("Add file evidence (JPG, PNG, PDF, TXT)") }
                        LoadingOutlinedButton(
                            onClick = onSubmitChat,
                            loading = submitting,
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text("Send chat as evidence") }
                    }
                }
            }
        }
    }

}
