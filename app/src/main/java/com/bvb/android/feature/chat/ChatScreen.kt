package com.bvb.android.feature.chat

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ReceiptLong
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.Button
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.hilt.navigation.compose.hiltViewModel
import com.bvb.android.ui.components.AvatarImage
import com.bvb.android.ui.components.ErrorBanner
import com.bvb.android.ui.components.FullScreenLoading
import com.bvb.android.ui.components.noAutofill
import com.bvb.android.ui.components.passwordContentType

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    onBack: () -> Unit,
    onOpenTrade: (String) -> Unit,
    viewModel: ChatViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsState()
    // Picks up a pending template (e.g. seller payment instructions), if any.
    var input by remember { mutableStateOf(ChatPrefill.consume(viewModel.tradeId) ?: "") }
    var lightbox by remember { mutableStateOf<ImageBitmap?>(null) }
    val listState = rememberLazyListState()

    LaunchedEffect(state.messages.size) {
        if (state.messages.isNotEmpty()) {
            listState.animateScrollToItem(state.messages.size - 1)
        }
    }

    // Full-screen viewer for a tapped image, like the web lightbox.
    lightbox?.let { bitmap ->
        Dialog(
            onDismissRequest = { lightbox = null },
            properties = DialogProperties(usePlatformDefaultWidth = false),
        ) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.92f))
                    .clickable { lightbox = null },
                contentAlignment = Alignment.Center,
            ) {
                Image(
                    bitmap = bitmap,
                    contentDescription = "Full size image",
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize().padding(12.dp),
                )
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Chat (end-to-end encrypted)") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    // Mirror of the web chat header: jump to the trade details.
                    IconButton(onClick = { onOpenTrade(viewModel.tradeId) }) {
                        Icon(Icons.AutoMirrored.Filled.ReceiptLong, contentDescription = "Trade details")
                    }
                },
            )
        }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).imePadding()) {
            state.error?.let { ErrorBanner(it, Modifier.padding(8.dp)) }

            // After a process restart the JWT survives but the PGP keys are
            // memory-only: ask the password to decrypt/send again.
            if (state.locked) {
                UnlockBanner(
                    unlocking = state.unlocking,
                    unlockError = state.unlockError,
                    biometric = viewModel.biometric,
                    onUnlock = { viewModel.unlock(it) },
                )
            }

            if (state.loading) {
                Box(Modifier.weight(1f)) { FullScreenLoading() }
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    contentPadding = PaddingValues(12.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    items(state.messages, key = { it.id }) { msg ->
                        MessageBubble(
                            msg = msg,
                            image = state.images[msg.id],
                            onImageClick = { lightbox = it },
                        )
                    }
                }
            }

            Row(
                Modifier.fillMaxWidth().padding(8.dp),
                verticalAlignment = Alignment.Bottom,
            ) {
                // Encrypted image attachments, like the web chat.
                val imagePicker = rememberLauncherForActivityResult(
                    ActivityResultContracts.GetContent()
                ) { uri -> uri?.let { viewModel.sendImage(it) } }
                IconButton(
                    onClick = { imagePicker.launch("image/*") },
                    enabled = !state.sending && state.canSend,
                ) {
                    Icon(Icons.Default.AttachFile, contentDescription = "Send image")
                }
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it },
                    placeholder = {
                        Text(
                            if (state.messagingAllowed) "Message…"
                            else "Messaging not available for this trade"
                        )
                    },
                    enabled = !state.sending && state.messagingAllowed,
                    modifier = Modifier.weight(1f).noAutofill(),
                    maxLines = 4,
                )
                IconButton(
                    onClick = {
                        val text = input.trim()
                        if (text.isNotEmpty()) {
                            viewModel.send(text)
                            input = ""
                        }
                    },
                    enabled = !state.sending && state.canSend,
                ) {
                    Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send")
                }
            }
        }
    }
}

@Composable
private fun UnlockBanner(
    unlocking: Boolean,
    unlockError: String?,
    biometric: com.bvb.android.core.security.BiometricUnlock,
    onUnlock: (String) -> Unit,
) {
    var password by remember { mutableStateOf("") }
    val bioAction = com.bvb.android.ui.components.rememberBiometricAction(
        biometric, "Unlock chat", "Authenticate to decrypt messages",
    )
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth().padding(8.dp),
    ) {
        Column(Modifier.padding(12.dp)) {
            Text(
                if (bioAction != null) "Chat locked: unlock to decrypt messages"
                else "Chat locked: enter your password to decrypt messages",
                style = MaterialTheme.typography.bodyMedium,
            )
            unlockError?.let {
                Spacer(Modifier.height(4.dp))
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
            Spacer(Modifier.height(8.dp))
            if (bioAction != null) {
                Button(
                    onClick = { if (!unlocking) bioAction { onUnlock(it) } },
                    enabled = !unlocking,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(if (unlocking) "…" else "Unlock") }
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = password,
                        onValueChange = { password = it },
                        placeholder = { Text("Password") },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        modifier = Modifier.weight(1f).passwordContentType(),
                    )
                    Spacer(Modifier.height(0.dp))
                    TextButton(
                        onClick = { if (password.isNotBlank()) onUnlock(password) },
                        enabled = !unlocking && password.isNotBlank(),
                    ) { Text(if (unlocking) "…" else "Unlock") }
                }
            }
        }
    }
}

@Composable
private fun MessageBubble(
    msg: ChatMessage,
    image: ChatImage?,
    onImageClick: (ImageBitmap) -> Unit,
) {
    val fromSelf = msg.isFromSelf
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = if (fromSelf) Arrangement.End else Arrangement.Start,
        verticalAlignment = Alignment.Bottom,
    ) {
        // As in the web app: counterparty avatar next to their bubbles.
        if (!fromSelf) {
            AvatarImage(msg.senderAvatar, sizeDp = 28)
            Spacer(Modifier.width(6.dp))
        }
        Surface(
            color = if (fromSelf) MaterialTheme.colorScheme.primary.copy(alpha = 0.25f)
            else MaterialTheme.colorScheme.surfaceVariant,
            shape = RoundedCornerShape(
                topStart = 12.dp, topEnd = 12.dp,
                bottomStart = if (fromSelf) 12.dp else 2.dp,
                bottomEnd = if (fromSelf) 2.dp else 12.dp,
            ),
        ) {
            Column(Modifier.padding(10.dp).widthIn(max = 300.dp)) {
                if (msg.isImage) {
                    when (image) {
                        is ChatImage.Ready -> Image(
                            bitmap = image.bitmap,
                            contentDescription = "Encrypted image",
                            contentScale = ContentScale.Fit,
                            modifier = Modifier
                                .widthIn(max = 240.dp)
                                .heightIn(max = 280.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .clickable { onImageClick(image.bitmap) },
                        )
                        ChatImage.Failed -> Text(
                            "[Image decryption failed]",
                            style = MaterialTheme.typography.bodyMedium,
                            fontStyle = FontStyle.Italic,
                            color = MaterialTheme.colorScheme.error,
                        )
                        else -> Text(
                            "Decrypting image…",
                            style = MaterialTheme.typography.bodyMedium,
                            fontStyle = FontStyle.Italic,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                } else if (msg.decryptionFailed) {
                    Text(
                        "[Unable to decrypt this message]",
                        style = MaterialTheme.typography.bodyMedium,
                        fontStyle = FontStyle.Italic,
                        color = MaterialTheme.colorScheme.error,
                    )
                } else {
                    SelectionContainer {
                        Text(msg.text, style = MaterialTheme.typography.bodyMedium)
                    }
                }
                Spacer(Modifier.height(2.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        msg.timeLabel,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (msg.signatureValid == false) {
                        Spacer(Modifier.height(2.dp))
                        Text(
                            " · unverified signature",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }
        }
    }
}
