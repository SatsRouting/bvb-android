package com.bvb.android.feature.chat

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bvb.android.core.AppLog
import com.bvb.android.core.network.ApiError
import com.bvb.android.core.network.ApiService
import com.bvb.android.core.pgp.PgpService
import com.bvb.android.core.session.SessionManager
import com.bvb.android.core.sse.SseClient
import com.bvb.android.data.model.SendMessageRequest
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.toRequestBody

// Same limits as the web client (MessageList.js).
private const val MAX_IMAGE_DIMENSION = 1280
private const val JPEG_QUALITY = 80
private const val MAX_FILE_SIZE = 2 * 1024 * 1024

data class ChatMessage(
    val id: String,
    val senderAvatar: String,
    val text: String,
    val isFromSelf: Boolean,
    val timeLabel: String,
    val decryptionFailed: Boolean = false,
    val signatureValid: Boolean? = null,
    val isImage: Boolean = false,
)

/** Decryption state of an image attachment. */
sealed interface ChatImage {
    data object Loading : ChatImage
    data object Failed : ChatImage
    data class Ready(val bitmap: ImageBitmap) : ChatImage
}

/** Same statuses that the web app allows for messaging (MessageList.js). */
private val MESSAGING_ALLOWED_STATUSES = setOf("funded", "payment_sent", "disputed")

data class ChatUiState(
    val loading: Boolean = true,
    val sending: Boolean = false,
    val canSend: Boolean = false,
    /** False when the trade status forbids messaging (e.g. completed/cancelled). */
    val messagingAllowed: Boolean = true,
    val error: String? = null,
    val messages: List<ChatMessage> = emptyList(),
    /** Decrypted image attachments keyed by message id. */
    val images: Map<String, ChatImage> = emptyMap(),
    /** True when the PGP keys are not in memory (app restarted): ask password. */
    val locked: Boolean = false,
    val unlocking: Boolean = false,
    val unlockError: String? = null,
)

@HiltViewModel
class ChatViewModel @Inject constructor(
    private val api: ApiService,
    private val pgp: PgpService,
    private val session: SessionManager,
    private val sse: SseClient,
    private val auth: com.bvb.android.data.repository.AuthRepository,
    val biometric: com.bvb.android.core.security.BiometricUnlock,
    @ApplicationContext private val appContext: Context,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {
    val tradeId: String = checkNotNull(savedStateHandle["tradeId"])
    val uiState = MutableStateFlow(ChatUiState())

    private var counterpartyPubKey: String? = null
    private val decryptingImages = mutableSetOf<String>()

    init {
        load()
        viewModelScope.launch {
            sse.events.collect { event ->
                when (event.type) {
                    "new_message", "message_sent", "trade_update", "trade_expired" -> {
                        val evTradeId = event.data?.jsonObject?.get("trade_id")?.jsonPrimitive?.content
                        if (evTradeId == tradeId) load(silent = true)
                    }
                    "sse_reconnected" -> load(silent = true)
                }
            }
        }
    }

    private fun load(silent: Boolean = false) {
        viewModelScope.launch {
            try {
                if (counterpartyPubKey == null) {
                    counterpartyPubKey = try {
                        api.getCounterpartyPubKey(tradeId).pgpPublicKey
                    } catch (e: Exception) {
                        null
                    }
                }
                // Messaging is only allowed in some trade statuses (as in the web
                // app); if the fetch fails keep the last known value.
                val messagingAllowed = try {
                    api.getTrade(tradeId).status in MESSAGING_ALLOWED_STATUSES
                } catch (e: Exception) {
                    uiState.value.messagingAllowed
                }
                val raw = api.getMessages(tradeId).orEmpty()
                val decrypted = withContext(Dispatchers.Default) {
                    raw.map { msg -> decryptMessage(msg) }
                }
                uiState.value = uiState.value.copy(
                    loading = false,
                    messages = decrypted,
                    canSend = counterpartyPubKey != null &&
                        session.pgpPrivateKeyArmored != null &&
                        messagingAllowed,
                    messagingAllowed = messagingAllowed,
                    locked = session.pgpPrivateKeyArmored == null || session.sessionPassword == null,
                    error = null,
                )
                // Fetch and decrypt image attachments not yet in the cache.
                decrypted.filter { it.isImage && uiState.value.images[it.id] !is ChatImage.Ready }
                    .forEach { decryptImage(it.id, it.isFromSelf) }
            } catch (t: Throwable) {
                // Throwable (not Exception): a JVM Error thrown by the PGP
                // stack must not crash the whole app.
                AppLog.e("BVB", "chat load failed", t)
                if (!silent) {
                    uiState.value = uiState.value.copy(loading = false, error = ApiError.messageOf(t))
                }
            }
        }
    }

    private fun decryptMessage(msg: com.bvb.android.data.model.EncryptedMessage): ChatMessage {
        val timeLabel = formatTime(msg.timestamp)
        if (msg.messageType == "image") {
            // The image body lives in a separate encrypted attachment.
            return ChatMessage(msg.id, msg.senderAvatar, "", msg.isFromSelf, timeLabel, isImage = true)
        }
        if (!msg.isEncrypted) {
            return ChatMessage(msg.id, msg.senderAvatar, msg.encryptedText, msg.isFromSelf, timeLabel)
        }
        val privateKey = session.pgpPrivateKeyArmored
        // The key is locked with "avatarId:password", not the bare password.
        val passphrase = session.pgpPassphrase
        if (privateKey == null || passphrase == null) {
            return ChatMessage(msg.id, msg.senderAvatar, "", msg.isFromSelf, timeLabel, decryptionFailed = true)
        }
        return try {
            val myUserId = session.userId.orEmpty()
            val armored = pgp.extractEncryptionForUser(msg.encryptedText, myUserId, msg.senderId)
            val plaintext = pgp.decryptMessage(armored, privateKey, passphrase)
            // Verify the detached signature with the sender's public key (own
            // messages verify against our key; counterparty's against theirs).
            val signatureValid = msg.signature?.let { sig ->
                val senderKey = if (msg.isFromSelf) session.pgpPublicKeyArmored else counterpartyPubKey
                senderKey?.let { pgp.verifyDetached(plaintext, sig, it) }
            }
            ChatMessage(msg.id, msg.senderAvatar, plaintext, msg.isFromSelf, timeLabel, signatureValid = signatureValid)
        } catch (t: Throwable) {
            AppLog.e("BVB", "message decryption failed", t)
            ChatMessage(msg.id, msg.senderAvatar, "", msg.isFromSelf, timeLabel, decryptionFailed = true)
        }
    }

    /** Re-unlocks the PGP keys with the account password and re-decrypts. */
    fun unlock(password: String) {
        uiState.value = uiState.value.copy(unlocking = true, unlockError = null)
        viewModelScope.launch {
            try {
                auth.unlockPgpKeys(password)
                uiState.value = uiState.value.copy(unlocking = false, locked = false)
                load(silent = true)
            } catch (t: Throwable) {
                AppLog.e("BVB", "pgp unlock failed", t)
                uiState.value = uiState.value.copy(
                    unlocking = false,
                    unlockError = ApiError.messageOf(t),
                )
            }
        }
    }

    fun send(text: String) {
        val recipientKey = counterpartyPubKey ?: return
        val myKey = session.pgpPublicKeyArmored ?: return
        val privateKey = session.pgpPrivateKeyArmored ?: return
        val passphrase = session.pgpPassphrase ?: return

        uiState.value = uiState.value.copy(sending = true)
        viewModelScope.launch {
            try {
                val payload = withContext(Dispatchers.Default) {
                    // Dual encryption, same envelope as the web client: one copy
                    // for the recipient, one readable by ourselves.
                    val forRecipient = pgp.encryptMessage(text, recipientKey)
                    val forSender = pgp.encryptMessage(text, myKey)
                    val envelope = buildJsonObject {
                        put("for_recipient", JsonPrimitive(forRecipient))
                        put("for_sender", JsonPrimitive(forSender))
                    }.toString()
                    val signature = pgp.signDetached(text, privateKey, passphrase)
                    SendMessageRequest(encryptedText = envelope, signature = signature)
                }
                api.sendMessage(tradeId, payload)
                load(silent = true)
            } catch (t: Throwable) {
                AppLog.e("BVB", "message send failed", t)
                uiState.value = uiState.value.copy(error = ApiError.messageOf(t))
            } finally {
                uiState.value = uiState.value.copy(sending = false)
            }
        }
    }

    /**
     * Downloads and decrypts an image attachment. The blob layout mirrors the
     * web client: [4-byte big-endian length][copy for recipient][copy for sender].
     */
    private fun decryptImage(messageId: String, isFromSelf: Boolean) {
        val privateKey = session.pgpPrivateKeyArmored ?: return
        val passphrase = session.pgpPassphrase ?: return
        if (!decryptingImages.add(messageId)) return
        uiState.update { it.copy(images = it.images + (messageId to ChatImage.Loading)) }
        viewModelScope.launch {
            try {
                val bitmap = withContext(Dispatchers.IO) {
                    val combined = api.getMessageAttachment(messageId).bytes()
                    val recipientLen = ByteBuffer.wrap(combined, 0, 4).int
                    val encrypted = if (isFromSelf) {
                        combined.copyOfRange(4 + recipientLen, combined.size)
                    } else {
                        combined.copyOfRange(4, 4 + recipientLen)
                    }
                    val decrypted = pgp.decryptBinary(encrypted, privateKey, passphrase)
                    BitmapFactory.decodeByteArray(decrypted, 0, decrypted.size)
                        ?: throw IllegalStateException("Invalid image data")
                }
                uiState.update {
                    it.copy(images = it.images + (messageId to ChatImage.Ready(bitmap.asImageBitmap())))
                }
            } catch (t: Throwable) {
                AppLog.e("BVB", "image decryption failed", t)
                uiState.update { it.copy(images = it.images + (messageId to ChatImage.Failed)) }
            } finally {
                decryptingImages.remove(messageId)
            }
        }
    }

    /** Compresses, dual-encrypts and uploads an image, like the web client. */
    fun sendImage(uri: Uri) {
        val recipientKey = counterpartyPubKey ?: return
        val myKey = session.pgpPublicKeyArmored ?: return

        uiState.value = uiState.value.copy(sending = true)
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val jpeg = compressImage(uri)
                    check(jpeg.size <= MAX_FILE_SIZE) { "Image too large even after compression" }

                    val forRecipient = pgp.encryptBinary(jpeg, recipientKey)
                    val forSender = pgp.encryptBinary(jpeg, myKey)
                    val combined = ByteBuffer.allocate(4 + forRecipient.size + forSender.size)
                        .putInt(forRecipient.size)
                        .put(forRecipient)
                        .put(forSender)
                        .array()

                    val part = MultipartBody.Part.createFormData(
                        "encrypted_image",
                        "image.bin",
                        combined.toRequestBody("application/octet-stream".toMediaType()),
                    )
                    api.sendImageMessage(
                        tradeId,
                        part,
                        contentType = "image/jpeg".toRequestBody("text/plain".toMediaType()),
                    )
                }
                load(silent = true)
            } catch (t: Throwable) {
                AppLog.e("BVB", "image send failed", t)
                val msg = (t as? IllegalStateException)?.message ?: ApiError.messageOf(t)
                uiState.value = uiState.value.copy(error = msg)
            } finally {
                uiState.value = uiState.value.copy(sending = false)
            }
        }
    }

    /** Downscales to max 1280px and re-encodes as JPEG 80, as the web does. */
    private fun compressImage(uri: Uri): ByteArray {
        val resolver = appContext.contentResolver

        // First pass: bounds only, to pick a power-of-two sample size.
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri).use { BitmapFactory.decodeStream(it, null, bounds) }
        check(bounds.outWidth > 0 && bounds.outHeight > 0) { "Unsupported or corrupted image" }

        var sampleSize = 1
        while (bounds.outWidth / (sampleSize * 2) >= MAX_IMAGE_DIMENSION ||
            bounds.outHeight / (sampleSize * 2) >= MAX_IMAGE_DIMENSION
        ) {
            sampleSize *= 2
        }

        val opts = BitmapFactory.Options().apply { inSampleSize = sampleSize }
        val decoded = resolver.openInputStream(uri).use { BitmapFactory.decodeStream(it, null, opts) }
            ?: throw IllegalStateException("Failed to load image")

        // Exact scale down to the max dimension, preserving aspect ratio.
        val ratio = minOf(
            MAX_IMAGE_DIMENSION.toFloat() / decoded.width,
            MAX_IMAGE_DIMENSION.toFloat() / decoded.height,
            1f,
        )
        val bitmap = if (ratio < 1f) {
            Bitmap.createScaledBitmap(
                decoded,
                (decoded.width * ratio).toInt(),
                (decoded.height * ratio).toInt(),
                true,
            )
        } else {
            decoded
        }

        val out = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
        return out.toByteArray()
    }

    private fun formatTime(iso: String): String = try {
        DateTimeFormatter.ofPattern("dd MMM HH:mm")
            .withZone(ZoneId.systemDefault())
            .format(Instant.parse(iso))
    } catch (e: Exception) {
        ""
    }
}
