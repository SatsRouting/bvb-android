package com.bvb.android.ui.components

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import java.time.Instant
import kotlinx.coroutines.delay

/**
 * Bottom sheet showing a Lightning invoice as QR + copyable bolt11 with an
 * expiry countdown. Payment confirmation arrives via SSE on the screen
 * underneath, so the sheet only needs a dismiss action.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InvoiceSheet(
    title: String,
    bolt11: String,
    amountSat: Long,
    expiresAt: String?,
    onDismiss: () -> Unit,
    dismissLabel: String = "Close",
    statusText: String? = null,
    /** Optional label/value rows shown instead of the big sats amount (web-style summary). */
    summaryRows: List<Pair<String, String>> = emptyList(),
    /** Optional reference line shown at the bottom, like the web's "Ref: …". */
    refText: String? = null,
) {
    val context = LocalContext.current
    var remaining by remember { mutableLongStateOf(remainingSeconds(expiresAt)) }

    LaunchedEffect(expiresAt) {
        while (true) {
            remaining = remainingSeconds(expiresAt)
            delay(1000)
        }
    }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier.fillMaxWidth().padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(title, style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(4.dp))
            if (summaryRows.isEmpty()) {
                Text(formatSats(amountSat), style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.primary)
            } else {
                Spacer(Modifier.height(4.dp))
                Column(
                    Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(10.dp))
                        .padding(horizontal = 14.dp, vertical = 6.dp),
                ) {
                    summaryRows.forEachIndexed { index, (label, value) ->
                        Row(
                            Modifier.fillMaxWidth().padding(vertical = 6.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Text(
                                label,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(
                                value,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold,
                            )
                        }
                        if (index < summaryRows.lastIndex) HorizontalDivider()
                    }
                }
                Spacer(Modifier.height(8.dp))
            }
            if (expiresAt != null) {
                Text(
                    if (remaining > 0) "Expires in ${formatCountdown(remaining)}" else "Invoice expired",
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (remaining > 0) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error,
                )
            }
            Spacer(Modifier.height(16.dp))
            QrCode(content = bolt11.uppercase())
            Spacer(Modifier.height(16.dp))
            statusText?.let {
                Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.height(8.dp))
            }
            OutlinedButton(
                onClick = {
                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    clipboard.setPrimaryClip(ClipData.newPlainText("invoice", bolt11))
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Copy invoice") }
            Spacer(Modifier.height(8.dp))
            Button(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) { Text(dismissLabel) }
            refText?.let {
                Spacer(Modifier.height(12.dp))
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

private fun remainingSeconds(expiresAt: String?): Long {
    if (expiresAt.isNullOrBlank()) return 0
    return try {
        Instant.parse(expiresAt).epochSecond - Instant.now().epochSecond
    } catch (e: Exception) {
        0
    }
}
